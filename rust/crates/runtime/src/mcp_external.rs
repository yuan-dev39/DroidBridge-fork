//! External MCP registry. Each configured server has an isolated client, session, cache and
//! credential. Tool names are namespaced as <server>__<tool>; native DroidBridge tools are untouched.

use reqwest::{header, Client, Url};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    collections::BTreeSet,
    net::IpAddr,
    time::Duration,
};
use tokio::{sync::Mutex, time::Instant};

const DEFAULT_ENDPOINT: &str = "http://127.0.0.1:8787/mcp";
const CACHE_TTL: Duration = Duration::from_secs(15);
const MAX_RESPONSE_BYTES: usize = 2 * 1024 * 1024;
const MAX_SERVERS: usize = 16;
const MAX_TOOLS_PER_SERVER: usize = 256;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct ServerConfig {
    name: String,
    endpoint: String,
    #[serde(default)]
    bearer_token: Option<String>,
}

#[derive(Default)]
struct RemoteState {
    initialized: bool,
    session_id: Option<String>,
    tools: Vec<Value>,
    refreshed_at: Option<Instant>,
    next_id: u64,
}

struct ExternalMcpServer {
    name: String,
    client: Client,
    endpoint: Url,
    bearer_token: Option<String>,
    state: Mutex<RemoteState>,
}

pub(crate) struct ExternalMcpRegistry {
    servers: Vec<ExternalMcpServer>,
}

impl ExternalMcpRegistry {
    pub(crate) fn new() -> Self {
        let configs = std::env::var("DROIDBRIDGE_EXTERNAL_MCP_SERVICES")
            .ok()
            .filter(|raw| raw.len() <= 65_536)
            .and_then(|raw| serde_json::from_str::<Vec<ServerConfig>>(&raw).ok())
            .filter(|configs| !configs.is_empty())
            .unwrap_or_else(|| {
                vec![ServerConfig {
                    name: "mt".to_owned(),
                    endpoint: std::env::var("DROIDBRIDGE_MT_MCP_URL")
                        .unwrap_or_else(|_| DEFAULT_ENDPOINT.to_owned()),
                    bearer_token: std::env::var("DROIDBRIDGE_MT_MCP_TOKEN").ok(),
                }]
            });

        let mut seen_names = BTreeSet::new();
        let servers = configs
            .into_iter()
            .take(MAX_SERVERS)
            .filter(|config| seen_names.insert(config.name.clone()))
            .filter_map(ExternalMcpServer::new)
            .collect();
        Self { servers }
    }

    /// Discovers tools from every configured server. One unavailable server does not prevent
    /// healthy servers or native DroidBridge tools from being listed.
    pub(crate) async fn tools(&self) -> Vec<Value> {
        let mut all = Vec::new();
        for server in &self.servers {
            all.extend(server.tools().await);
        }
        all
    }

    /// Routes a namespaced tool call to exactly one configured server.
    pub(crate) async fn call(&self, qualified_name: &str, arguments: Value) -> Result<Value, String> {
        let (server_name, tool_name) = qualified_name
            .split_once("__")
            .ok_or_else(|| "external tool name has no service namespace".to_owned())?;
        let server = self.servers.iter()
            .find(|server| server.name == server_name)
            .ok_or_else(|| "external MCP service is not configured".to_owned())?;
        server.call(tool_name, arguments).await
    }
}

impl ExternalMcpServer {
    fn new(config: ServerConfig) -> Option<Self> {
        if !valid_service_name(&config.name) || config.endpoint.len() > 2048 {
            return None;
        }
        let endpoint = safe_endpoint(&config.endpoint)?;
        let bearer_token = config.bearer_token.filter(|token| {
            !token.is_empty() && token.len() <= 4096 && !token.contains('\r') && !token.contains('\n')
        });
        let client = Client::builder()
            .connect_timeout(Duration::from_secs(3))
            .timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .ok()?;
        Some(Self {
            name: config.name,
            client,
            endpoint,
            bearer_token,
            state: Mutex::new(RemoteState::default()),
        })
    }

    async fn tools(&self) -> Vec<Value> {
        let mut state = self.state.lock().await;
        if state.refreshed_at.is_some_and(|at| at.elapsed() < CACHE_TTL) {
            return namespace_tools(&self.name, &state.tools);
        }
        let result = async {
            self.ensure_initialized(&mut state).await?;
            let response = self.rpc(&mut state, "tools/list", json!({})).await?;
            let tools = response.pointer("/result/tools")
                .and_then(Value::as_array)
                .cloned()
                .ok_or_else(|| "remote tools/list reply has no tools array".to_owned())?;
            if tools.len() > MAX_TOOLS_PER_SERVER {
                return Err("remote MCP tool count exceeds the configured limit".to_owned());
            }
            Ok::<_, String>(tools)
        }.await;
        match result {
            Ok(tools) => {
                state.tools = tools;
                state.refreshed_at = Some(Instant::now());
            }
            Err(_) => {
                // Keep the last known-good tool list, but retry discovery on the next request.
                state.refreshed_at = None;
            }
        }
        namespace_tools(&self.name, &state.tools)
    }

    async fn call(&self, tool_name: &str, arguments: Value) -> Result<Value, String> {
        if tool_name.is_empty() || tool_name.len() > 128 {
            return Err("remote tool name is invalid".to_owned());
        }
        let mut state = self.state.lock().await;
        self.ensure_initialized(&mut state).await?;
        if !state.tools.iter().any(|tool| tool.get("name").and_then(Value::as_str) == Some(tool_name)) {
            let response = self.rpc(&mut state, "tools/list", json!({})).await?;
            state.tools = response.pointer("/result/tools")
                .and_then(Value::as_array)
                .cloned()
                .ok_or_else(|| "remote tools/list reply has no tools array".to_owned())?;
            if state.tools.len() > MAX_TOOLS_PER_SERVER {
                return Err("remote MCP tool count exceeds the configured limit".to_owned());
            }
            state.refreshed_at = Some(Instant::now());
        }
        if !state.tools.iter().any(|tool| tool.get("name").and_then(Value::as_str) == Some(tool_name)) {
            return Err("remote tool is not currently advertised".to_owned());
        }
        let response = self.rpc(
            &mut state,
            "tools/call",
            json!({"name": tool_name, "arguments": arguments}),
        ).await?;
        response.get("result").cloned()
            .ok_or_else(|| "remote tools/call reply has no result".to_owned())
    }

    async fn ensure_initialized(&self, state: &mut RemoteState) -> Result<(), String> {
        if state.initialized {
            return Ok(());
        }
        let response = self.rpc(
            state,
            "initialize",
            json!({
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "droidbridge-mcp-proxy", "version": "0.5.9"}
            }),
        ).await?;
        if response.get("result").is_none() {
            return Err("remote MCP initialize reply is invalid".to_owned());
        }
        state.initialized = true;

        // Best-effort notification: some compatible MCP servers are stateless.
        let mut request = self.client.post(self.endpoint.clone())
            .header(header::CONTENT_TYPE, "application/json")
            .header(header::ACCEPT, "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-03-26")
            .json(&json!({"jsonrpc":"2.0","method":"notifications/initialized"}));
        if let Some(session) = &state.session_id {
            request = request.header("MCP-Session-Id", session);
        }
        if let Some(token) = &self.bearer_token {
            request = request.bearer_auth(token);
        }
        let _ = request.send().await;
        Ok(())
    }

    async fn rpc(&self, state: &mut RemoteState, method: &str, params: Value) -> Result<Value, String> {
        state.next_id = state.next_id.saturating_add(1).max(1);
        let payload = json!({
            "jsonrpc": "2.0",
            "id": state.next_id,
            "method": method,
            "params": params
        });
        let mut request = self.client.post(self.endpoint.clone())
            .header(header::CONTENT_TYPE, "application/json")
            .header(header::ACCEPT, "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-03-26")
            .json(&payload);
        if let Some(session) = &state.session_id {
            request = request.header("MCP-Session-Id", session);
        }
        if let Some(token) = &self.bearer_token {
            request = request.bearer_auth(token);
        }
        let response = request.send().await
            .map_err(|_| "remote MCP endpoint is unavailable".to_owned())?;
        if let Some(session) = response.headers().get("MCP-Session-Id")
            .and_then(|value| value.to_str().ok()) {
            state.session_id = Some(session.to_owned());
        }
        if !response.status().is_success() {
            return Err(format!("remote MCP endpoint returned HTTP {}", response.status().as_u16()));
        }
        let content_type = response.headers().get(header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or("")
            .to_ascii_lowercase();
        let bytes = response.bytes().await
            .map_err(|_| "remote MCP response could not be read".to_owned())?;
        if bytes.len() > MAX_RESPONSE_BYTES {
            return Err("remote MCP response exceeds the 2 MiB limit".to_owned());
        }
        let text = String::from_utf8(bytes.to_vec())
            .map_err(|_| "remote MCP response is not UTF-8".to_owned())?;
        let body = if content_type.starts_with("text/event-stream") {
            text.lines()
                .filter_map(|line| line.strip_prefix("data:"))
                .map(str::trim)
                .filter(|line| !line.is_empty())
                .last()
                .ok_or_else(|| "remote MCP SSE response contains no data event".to_owned())?
                .to_owned()
        } else {
            text
        };
        let value: Value = serde_json::from_str(&body)
            .map_err(|_| "remote MCP response is not valid JSON-RPC".to_owned())?;
        if let Some(error) = value.get("error") {
            return Err(error.get("message").and_then(Value::as_str)
                .unwrap_or("remote MCP error").to_owned());
        }
        Ok(value)
    }
}

fn valid_service_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 24
        && name.bytes().all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_' || byte == b'-')
}

fn safe_endpoint(raw: &str) -> Option<Url> {
    let url = Url::parse(raw).ok()?;
    if !matches!(url.scheme(), "http" | "https")
        || url.path() != "/mcp"
        || url.query().is_some()
        || url.fragment().is_some()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return None;
    }
    let host = url.host_str()?;
    let private_http = if url.scheme() == "https" {
        true
    } else if host.eq_ignore_ascii_case("localhost") || host.ends_with(".localhost") || host.ends_with(".local") {
        true
    } else if let Ok(ip) = host.parse::<IpAddr>() {
        match ip {
            IpAddr::V4(ip) => ip.is_loopback() || ip.is_private() || ip.is_link_local(),
            IpAddr::V6(ip) => ip.is_loopback() || ip.is_unique_local() || ip.is_unicast_link_local(),
        }
    } else {
        false
    };
    private_http.then_some(url)
}

fn namespace_tools(service: &str, tools: &[Value]) -> Vec<Value> {
    tools.iter().filter_map(|tool| {
        let name = tool.get("name")?.as_str()?;
        if name.is_empty() || name.len() > 128 || name.contains("__") {
            return None;
        }
        let mut tool = tool.clone();
        let object = tool.as_object_mut()?;
        object.insert("name".to_owned(), Value::String(format!("{service}__{name}")));
        if !object.get("inputSchema").is_some_and(Value::is_object) {
            object.insert("inputSchema".to_owned(), json!({
                "type": "object",
                "properties": {},
                "additionalProperties": true
            }));
        }
        Some(tool)
    }).collect()
}

pub(crate) fn is_external_tool(name: &str) -> bool {
    name.split_once("__").is_some_and(|(service, tool)| {
        valid_service_name(service) && !tool.is_empty()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn namespaced_tools_keep_the_original_schema() {
        let original = json!({
            "name": "shell",
            "description": "Run a shell command",
            "inputSchema": {"type": "object", "properties": {"command": {"type": "string"}}}
        });
        let namespaced = namespace_tools("mt", std::slice::from_ref(&original));
        assert_eq!(namespaced[0]["name"], "mt__shell");
        assert_eq!(namespaced[0]["inputSchema"]["properties"]["command"]["type"], "string");
        assert_eq!(original["name"], "shell");
    }

    #[test]
    fn missing_schemas_get_a_safe_object_schema() {
        let namespaced = namespace_tools("files", &[json!({"name": "inspect"})]);
        assert_eq!(namespaced[0]["inputSchema"]["type"], "object");
    }

    #[test]
    fn malformed_tools_are_ignored() {
        let tools = vec![
            json!({"description": "missing name"}),
            json!({"name": "", "inputSchema": {"type": "object"}}),
            json!({"name": "bad__namespace", "inputSchema": {"type": "object"}}),
            json!({"name": "valid", "inputSchema": {"type": "object"}}),
        ];
        let namespaced = namespace_tools("mt", &tools);
        assert_eq!(namespaced.len(), 1);
        assert_eq!(namespaced[0]["name"], "mt__valid");
    }

    #[test]
    fn service_names_and_endpoints_are_constrained() {
        assert!(valid_service_name("mt"));
        assert!(valid_service_name("my_service-2"));
        assert!(!valid_service_name("MT"));
        assert!(!valid_service_name("bad__name"));
        assert!(safe_endpoint("http://127.0.0.1:8787/mcp").is_some());
        assert!(safe_endpoint("http://192.168.1.10:8787/mcp").is_some());
        assert!(safe_endpoint("https://example.com/mcp").is_some());
        assert!(safe_endpoint("http://example.com/mcp").is_none());
        assert!(safe_endpoint("http://127.0.0.1/other").is_none());
        assert!(safe_endpoint("http://user:pass@127.0.0.1/mcp").is_none());
    }

    #[test]
    fn only_namespaced_tools_are_routed_to_external_servers() {
        assert!(is_external_tool("mt__shell"));
        assert!(is_external_tool("files__read"));
        assert!(!is_external_tool("shell"));
        assert!(!is_external_tool("mt__"));
        assert!(!is_external_tool("MT__shell"));
    }
}
