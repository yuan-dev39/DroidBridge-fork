//! Dynamic upstream MCP registry primitives. The daemon owns persistence and supplies this
//! shared registry to each facade instance; credentials never leave this module's call path.
use reqwest::{Client, Url, header::{AUTHORIZATION, CONTENT_TYPE, ACCEPT, HeaderValue}};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{collections::BTreeMap, net::IpAddr, sync::{Arc, RwLock}, time::Duration};
use tokio::time::timeout;

pub const MAX_SERVERS: usize = 16;
pub const MAX_TOOLS_PER_SERVER: usize = 128;
pub const MAX_RESPONSE_BYTES: usize = 2 * 1024 * 1024;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(12);

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct McpServerConfig {
    pub id: String,
    pub display_name: String,
    pub endpoint: String,
    #[serde(default = "default_enabled")]
    pub enabled: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bearer_token: Option<String>,
}
fn default_enabled() -> bool { true }

#[derive(Clone, Debug, PartialEq)]
pub struct PublishedTool {
    pub server_id: String,
    pub upstream_name: String,
    pub definition: Value,
    pub published_name: String,
}

#[derive(Clone, Default)]
pub struct DynamicMcpRegistry {
    servers: Arc<RwLock<BTreeMap<String, ServerEntry>>>,
}
#[derive(Clone, Default)]
struct ServerEntry {
    config: Option<McpServerConfig>,
    tools: Vec<PublishedTool>,
    protocol_version: Option<String>,
    session_id: Option<String>,
    last_error: Option<String>,
}
#[derive(Debug)]
pub enum RegistryError {
    Invalid(&'static str),
    Unavailable,
    Upstream(&'static str),
    Conflict,
}
impl DynamicMcpRegistry {
    pub fn new() -> Self { Self::default() }

    pub fn validate_config(config: &McpServerConfig) -> Result<Url, RegistryError> {
        if !valid_id(&config.id) || config.display_name.trim().is_empty()
            || config.display_name.len() > 80 || config.endpoint.len() > 512 {
            return Err(RegistryError::Invalid("invalid server identity"));
        }
        if config.bearer_token.as_ref().is_some_and(|v| v.is_empty() || v.len() > 512 || !v.bytes().all(|b| b.is_ascii_graphic())) {
            return Err(RegistryError::Invalid("invalid bearer credential"));
        }
        let url = Url::parse(&config.endpoint).map_err(|_| RegistryError::Invalid("invalid endpoint URL"))?;
        if url.scheme() != "http" || !url.username().is_empty() || url.password().is_some()
            || url.query().is_some() || url.fragment().is_some() {
            return Err(RegistryError::Invalid("endpoint must be plain HTTP without userinfo, query or fragment"));
        }
        let parsed_host = url.host_str().and_then(|host| host.parse::<IpAddr>().ok());
        let loopback = match parsed_host {
            Some(IpAddr::V4(ip)) => ip.is_loopback(),
            Some(IpAddr::V6(ip)) => ip.is_loopback(),
            None => false,
        };
        let allowed = match parsed_host {
            Some(IpAddr::V4(ip)) => loopback || ip.is_private() || ip.is_link_local(),
            Some(IpAddr::V6(ip)) => loopback || ip.is_unique_local(),
            None => false,
        };
        if !allowed {
            return Err(RegistryError::Invalid("only literal loopback or private IP endpoints are allowed"));
        }
        if config.bearer_token.is_some() && !loopback {
            return Err(RegistryError::Invalid("bearer credentials are allowed only for loopback endpoints over HTTP"));
        }
        Ok(url)
    }

    pub fn upsert(&self, config: McpServerConfig) -> Result<(), RegistryError> {
        Self::validate_config(&config)?;
        let mut entries = self.servers.write().map_err(|_| RegistryError::Unavailable)?;
        if !entries.contains_key(&config.id) && entries.len() >= MAX_SERVERS { return Err(RegistryError::Invalid("server limit reached")); }
        let old = entries.get(&config.id).cloned().filter(|entry| {
            entry.config.as_ref().is_some_and(|current|
                current.endpoint == config.endpoint && current.bearer_token == config.bearer_token)
        }).unwrap_or_default();
        entries.insert(config.id.clone(), ServerEntry { config: Some(config), ..old });
        Ok(())
    }
    pub fn configs(&self) -> Result<Vec<McpServerConfig>, RegistryError> {
        let entries = self.servers.read().map_err(|_| RegistryError::Unavailable)?;
        Ok(entries.values().filter_map(|entry| entry.config.clone()).collect())
    }

    pub fn list_servers(&self) -> Result<Vec<Value>, RegistryError> {
        let entries = self.servers.read().map_err(|_| RegistryError::Unavailable)?;
        Ok(entries.iter().map(|(id, entry)| json!({
            "id": id,
            "display_name": entry.config.as_ref().map(|c| c.display_name.as_str()).unwrap_or(""),
            "endpoint": entry.config.as_ref().map(|c| c.endpoint.as_str()).unwrap_or(""),
            "enabled": entry.config.as_ref().is_some_and(|c| c.enabled),
            "tool_count": entry.tools.len(),
            "connected": entry.last_error.is_none() && !entry.tools.is_empty(),
            "error": entry.last_error,
            "has_credential": entry.config.as_ref().is_some_and(|c| c.bearer_token.is_some())
        })).collect())
    }

    pub fn remove(&self, id: &str) -> Result<(), RegistryError> {
        self.servers.write().map_err(|_| RegistryError::Unavailable)?.remove(id);
        Ok(())
    }
    pub fn set_enabled(&self, id: &str, enabled: bool) -> Result<(), RegistryError> {
        let mut entries = self.servers.write().map_err(|_| RegistryError::Unavailable)?;
        let entry = entries.get_mut(id).ok_or(RegistryError::Invalid("unknown server"))?;
        entry.config.as_mut().ok_or(RegistryError::Unavailable)?.enabled = enabled;
        Ok(())
    }
    pub fn list_tools(&self) -> Result<Vec<PublishedTool>, RegistryError> {
        let entries = self.servers.read().map_err(|_| RegistryError::Unavailable)?;
        let mut out = Vec::new();
        for (id, entry) in entries.iter() {
            if entry.config.as_ref().is_some_and(|c| c.enabled) { out.extend(entry.tools.clone()); }
            // The service ID is part of the public name to prevent cross-server collisions.
            for tool in out.iter_mut().filter(|t| t.server_id == *id) {
                tool.published_name = format!("{}__{}", id, tool.upstream_name);
            }
        }
        Ok(out)
    }

    pub async fn refresh(&self, id: &str) -> Result<usize, RegistryError> {
        let config = {
            let entries = self.servers.read().map_err(|_| RegistryError::Unavailable)?;
            entries.get(id).and_then(|e| e.config.clone()).ok_or(RegistryError::Invalid("unknown server"))?
        };
        if !config.enabled { return Err(RegistryError::Invalid("server is disabled")); }
        let (tools, protocol_version, session_id) = match discover(&config).await {
            Ok(discovered) => discovered,
            Err(error) => {
                if let Ok(mut entries) = self.servers.write() {
                    if let Some(entry) = entries.get_mut(id) {
                        entry.last_error = Some("discovery_failed".to_owned());
                    }
                }
                return Err(error);
            }
        };
        let mut names = std::collections::BTreeSet::new();
        for tool in &tools {
            let name = tool.get("name").and_then(Value::as_str).ok_or(RegistryError::Upstream("tool has no name"))?;
            if !valid_tool_name(name) || !names.insert(name.to_owned()) || !tool.get("inputSchema").is_some_and(Value::is_object) {
                return Err(RegistryError::Upstream("invalid or duplicate tool definition"));
            }
        }
        if tools.len() > MAX_TOOLS_PER_SERVER { return Err(RegistryError::Invalid("tool limit reached")); }
        let mut entries = self.servers.write().map_err(|_| RegistryError::Unavailable)?;
        let entry = entries.get_mut(id).ok_or(RegistryError::Invalid("server was removed"))?;
        entry.tools = tools.into_iter().map(|definition| {
            let upstream_name = definition["name"].as_str().unwrap_or_default().to_owned();
            let mut definition = definition;
            definition["name"] = json!(format!("{}__{}", id, upstream_name));
            PublishedTool { server_id: id.to_owned(), upstream_name, definition: definition.clone(), published_name: definition["name"].as_str().unwrap_or_default().to_owned() }
        }).collect();
        entry.protocol_version = Some(protocol_version);
        entry.session_id = session_id;
        entry.last_error = None;
        Ok(entry.tools.len())
    }

    pub async fn call(&self, published_name: &str, arguments: Value) -> Result<Value, RegistryError> {
        let (config, upstream_name, session, protocol_version) = {
            let entries = self.servers.read().map_err(|_| RegistryError::Unavailable)?;
            let entry = entries.values().find(|e| e.config.as_ref().is_some_and(|c| c.enabled)
                && e.tools.iter().any(|t| t.published_name == published_name))
                .ok_or(RegistryError::Invalid("unknown or disabled tool"))?;
            let tool = entry.tools.iter().find(|t| t.published_name == published_name).ok_or(RegistryError::Unavailable)?;
            (
                entry.config.clone().ok_or(RegistryError::Unavailable)?,
                tool.upstream_name.clone(),
                entry.session_id.clone(),
                entry.protocol_version.clone(),
            )
        };
        let response = send_rpc(
            &config,
            session.as_deref(),
            protocol_version.as_deref(),
            "tools/call",
            json!({"name":upstream_name,"arguments":arguments}),
        ).await?;
        if response.value.get("error").is_some() { return Err(RegistryError::Upstream("upstream tool call failed")); }
        response.value.get("result").cloned().ok_or(RegistryError::Upstream("missing tool result"))
    }
}
fn valid_id(v: &str) -> bool {
    !v.is_empty()
        && v.len() <= 32
        && !v.contains("__")
        && !v.ends_with('_')
        && v.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_')
}
fn valid_tool_name(v: &str) -> bool {
    !v.is_empty()
        && v.len() <= 128
        && !v.starts_with('_')
        && v.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-' || b == b'.')
}

#[derive(Debug)]
struct RpcReply {
    value: Value,
    session_id: Option<String>,
}

async fn discover(config: &McpServerConfig) -> Result<(Vec<Value>, String, Option<String>), RegistryError> {
    let init = send_rpc(config, None, None, "initialize", json!({
        "protocolVersion":"2025-03-26",
        "capabilities":{},
        "clientInfo":{"name":"DroidBridge","version":env!("CARGO_PKG_VERSION")}
    })).await?;
    let version = init.value.pointer("/result/protocolVersion")
        .and_then(Value::as_str)
        .ok_or(RegistryError::Upstream("initialize did not negotiate a protocol version"))?
        .to_owned();
    let session = init.session_id.clone();
    let _ = send_rpc(config, session.as_deref(), Some(&version), "notifications/initialized", json!({})).await;

    let mut all_tools = Vec::new();
    let mut cursor: Option<String> = None;
    loop {
        let params = cursor.as_ref().map(|value| json!({"cursor":value})).unwrap_or_else(|| json!({}));
        let listed = send_rpc(config, session.as_deref(), Some(&version), "tools/list", params).await?;
        let page = listed.value.pointer("/result/tools").and_then(Value::as_array)
            .ok_or(RegistryError::Upstream("tools/list response is invalid"))?;
        if all_tools.len().saturating_add(page.len()) > MAX_TOOLS_PER_SERVER {
            return Err(RegistryError::Invalid("tool limit reached"));
        }
        all_tools.extend(page.iter().cloned());
        cursor = listed.value.pointer("/result/nextCursor").and_then(Value::as_str).map(str::to_owned);
        if cursor.is_none() { break; }
    }
    Ok((all_tools, version, session))
}

async fn send_rpc(
    config: &McpServerConfig,
    session: Option<&str>,
    protocol_version: Option<&str>,
    method: &str,
    params: Value,
) -> Result<RpcReply, RegistryError> {
    let url = DynamicMcpRegistry::validate_config(config)?;
    let client = Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(4))
        .timeout(REQUEST_TIMEOUT)
        .tls_backend_preconfigured(crate::network_tls_client_config().as_ref().clone())
        .build()
        .map_err(|_| RegistryError::Unavailable)?;
    let mut request = client.post(url)
        .header(CONTENT_TYPE, "application/json")
        .header(ACCEPT, "application/json, text/event-stream");
    if let Some(token) = &config.bearer_token {
        let mut value = HeaderValue::from_str(&format!("Bearer {token}"))
            .map_err(|_| RegistryError::Invalid("invalid credential header"))?;
        value.set_sensitive(true);
        request = request.header(AUTHORIZATION, value);
    }
    if let Some(version) = protocol_version {
        request = request.header("MCP-Protocol-Version", version);
    }
    if let Some(session) = session {
        request = request.header("Mcp-Session-Id", session);
    }
    let id = uuid::Uuid::new_v4().to_string();
    let body = if method.starts_with("notifications/") {
        json!({"jsonrpc":"2.0","method":method,"params":params})
    } else {
        json!({"jsonrpc":"2.0","id":id,"method":method,"params":params})
    };
    let mut response = timeout(REQUEST_TIMEOUT, request.json(&body).send()).await
        .map_err(|_| RegistryError::Unavailable)?
        .map_err(|_| RegistryError::Unavailable)?;
    if !response.status().is_success() {
        return Err(RegistryError::Upstream("upstream returned a non-success status"));
    }
    let session_id = response.headers().get("Mcp-Session-Id")
        .and_then(|value| value.to_str().ok()).map(str::to_owned);
    if method.starts_with("notifications/") {
        return Ok(RpcReply { value: json!({}), session_id });
    }
    let is_sse = response.headers().get(CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.starts_with("text/event-stream"));
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| RegistryError::Unavailable)? {
        if bytes.len().saturating_add(chunk.len()) > MAX_RESPONSE_BYTES {
            return Err(RegistryError::Invalid("upstream response too large"));
        }
        bytes.extend_from_slice(&chunk);
        if is_sse && (bytes.windows(2).any(|window| window == b"\n\n")
            || bytes.windows(4).any(|window| window == b"\r\n\r\n")) {
            break;
        }
    }
    let payload = if is_sse {
        let text = String::from_utf8(bytes)
            .map_err(|_| RegistryError::Upstream("upstream SSE is not UTF-8"))?;
        text.lines().filter_map(|line| line.strip_prefix("data:"))
            .map(str::trim).filter(|line| !line.is_empty()).last()
            .ok_or(RegistryError::Upstream("upstream SSE has no data event"))?
            .to_owned()
    } else {
        String::from_utf8(bytes)
            .map_err(|_| RegistryError::Upstream("upstream response is not UTF-8"))?
    };
    let value: Value = serde_json::from_str(&payload)
        .map_err(|_| RegistryError::Upstream("upstream returned invalid JSON"))?;
    if value.get("id").and_then(Value::as_str) != Some(id.as_str()) {
        return Err(RegistryError::Upstream("upstream response id does not match request"));
    }
    Ok(RpcReply { value, session_id })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn config(endpoint: &str) -> McpServerConfig { McpServerConfig { id:"mt-manager".into(), display_name:"MT Manager".into(), endpoint:endpoint.into(), enabled:true, bearer_token:None } }
    #[test] fn accepts_private_literal_endpoints_only() {
        assert!(DynamicMcpRegistry::validate_config(&config("http://127.0.0.1:8787/mcp")).is_ok());
        assert!(DynamicMcpRegistry::validate_config(&config("http://10.0.0.8:8787/mcp")).is_ok());
        assert!(DynamicMcpRegistry::validate_config(&config("https://example.com/mcp")).is_err());
        assert!(DynamicMcpRegistry::validate_config(&config("http://example.com/mcp")).is_err());
        assert!(DynamicMcpRegistry::validate_config(&config("http://127.0.0.1/mcp?token=x")).is_err());
    }
    #[test] fn names_are_stable_and_service_scoped() {
        assert!(valid_id("mt-manager"));
        assert!(!valid_id("../evil"));
        assert!(valid_tool_name("mt_apk_workspace_open"));
        assert!(!valid_tool_name("bad name"));
    }

    #[test]
    fn registry_redacts_credentials_and_hides_disabled_servers() {
        let registry = DynamicMcpRegistry::new();
        let mut server = config("http://127.0.0.1:8787/mcp");
        server.bearer_token = Some("local-secret".to_owned());
        registry.upsert(server).unwrap();
        let listed = registry.list_servers().unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0]["has_credential"], true);
        assert!(listed[0].get("bearer_token").is_none());
        assert!(registry.list_tools().unwrap().is_empty());
        registry.set_enabled("mt-manager", false).unwrap();
        assert_eq!(registry.list_servers().unwrap()[0]["enabled"], false);
        assert!(registry.list_tools().unwrap().is_empty());
    }

    fn read_mock_request(stream: &std::net::TcpStream) -> (String, Value) {
        use std::io::{BufRead, BufReader, Read};
        let mut reader = BufReader::new(stream.try_clone().unwrap());
        let mut headers = String::new();
        let mut line = String::new();
        reader.read_line(&mut line).unwrap();
        headers.push_str(&line);
        let mut length = 0_usize;
        loop {
            line.clear();
            reader.read_line(&mut line).unwrap();
            if line == "\r\n" || line.is_empty() { break; }
            if let Some((name, value)) = line.split_once(':') {
                if name.eq_ignore_ascii_case("content-length") {
                    length = value.trim().parse().unwrap();
                }
            }
            headers.push_str(&line);
        }
        let mut body = vec![0_u8; length];
        reader.read_exact(&mut body).unwrap();
        (headers, serde_json::from_slice(&body).unwrap())
    }

    fn write_mock_response(stream: &mut std::net::TcpStream, status: &str, headers: &str, body: &Value) {
        use std::io::Write;
        let body = serde_json::to_vec(body).unwrap();
        write!(stream, "HTTP/1.1 {status}\r\n{headers}Content-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len()).unwrap();
        stream.write_all(&body).unwrap();
    }

    #[test]
    fn discovers_and_calls_a_streamable_http_server_with_its_session() {
        use std::{io::Write, net::TcpListener, thread};
        let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = thread::spawn(move || {
            for step in 0..4 {
                let (mut stream, _) = listener.accept().unwrap();
                let (headers, request) = read_mock_request(&stream);
                let method = request["method"].as_str().unwrap();
                match (step, method) {
                    (0, "initialize") => {
                        write_mock_response(&mut stream, "200 OK", "Mcp-Session-Id: session-test\r\n", &json!({
                            "jsonrpc":"2.0","id":request["id"],
                            "result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}}}
                        }));
                    }
                    (1, "notifications/initialized") => {
                        assert!(headers.to_ascii_lowercase().contains("mcp-session-id: session-test"));
                        stream.write_all(b"HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").unwrap();
                    }
                    (2, "tools/list") => {
                        assert!(headers.to_ascii_lowercase().contains("mcp-session-id: session-test"));
                        assert!(headers.to_ascii_lowercase().contains("mcp-protocol-version: 2025-03-26"));
                        write_mock_response(&mut stream, "200 OK", "Mcp-Session-Id: session-test\r\n", &json!({
                            "jsonrpc":"2.0","id":request["id"],"result":{"tools":[{
                                "name":"echo","description":"Echo input",
                                "inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}
                            }]}
                        }));
                    }
                    (3, "tools/call") => {
                        assert!(headers.to_ascii_lowercase().contains("mcp-session-id: session-test"));
                        assert_eq!(request["params"]["name"], "echo");
                        assert_eq!(request["params"]["arguments"]["text"], "hello");
                        write_mock_response(&mut stream, "200 OK", "Mcp-Session-Id: session-test\r\n", &json!({
                            "jsonrpc":"2.0","id":request["id"],"result":{
                                "content":[{"type":"text","text":"hello"}],
                                "structuredContent":{"echo":"hello"}
                            }
                        }));
                    }
                    other => panic!("unexpected mock MCP exchange: {other:?}"),
                }
            }
        });

        let registry = DynamicMcpRegistry::new();
        registry.upsert(config(&format!("http://127.0.0.1:{port}/mcp"))).unwrap();
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            assert_eq!(registry.refresh("mt-manager").await.unwrap(), 1);
            let tools = registry.list_tools().unwrap();
            assert_eq!(tools[0].published_name, "mt-manager__echo");
            let result = registry.call("mt-manager__echo", json!({"text":"hello"})).await.unwrap();
            assert_eq!(result["structuredContent"]["echo"], "hello");
        });
        server.join().unwrap();
    }
}
