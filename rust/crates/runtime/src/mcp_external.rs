//! A narrowly scoped Streamable HTTP proxy for the MT Manager MCP endpoint.
//! The endpoint is loopback-only by default; remote tools are namespaced as mt__<tool>.

use reqwest::{header, Client, Url};
use serde_json::{json, Value};
use std::{
    sync::Arc,
    time::Duration,
};
use tokio::{sync::Mutex, time::Instant};

const DEFAULT_ENDPOINT: &str = "http://127.0.0.1:8787/mcp";
const TOOL_PREFIX: &str = "mt__";
const CACHE_TTL: Duration = Duration::from_secs(15);
const MAX_RESPONSE_BYTES: usize = 2 * 1024 * 1024;

#[derive(Default)]
struct RemoteState {
    initialized: bool,
    session_id: Option<String>,
    tools: Vec<Value>,
    refreshed_at: Option<Instant>,
    next_id: u64,
}

pub(crate) struct ExternalMcpRegistry {
    client: Client,
    endpoint: Url,
    bearer_token: Option<String>,
    state: Arc<Mutex<RemoteState>>,
}

impl ExternalMcpRegistry {
    pub(crate) fn new() -> Self {
        let endpoint_text = std::env::var("DROIDBRIDGE_MT_MCP_URL")
            .unwrap_or_else(|_| DEFAULT_ENDPOINT.to_owned());
        // Only loopback HTTP endpoints are accepted. This prevents a debug build or a modified
        // environment from turning the proxy into an arbitrary network request primitive.
        let endpoint = Url::parse(&endpoint_text)
            .ok()
            .filter(|url| {
                url.scheme() == "http"
                    && url.path() == "/mcp"
                    && url.host_str().is_some_and(|host| {
                        host.eq_ignore_ascii_case("localhost") || host == "127.0.0.1"
                    })
            })
            .unwrap_or_else(|| Url::parse(DEFAULT_ENDPOINT).expect("static endpoint is valid"));
        let bearer_token = std::env::var("DROIDBRIDGE_MT_MCP_TOKEN")
            .ok()
            .filter(|token| !token.is_empty());
        let client = Client::builder()
            .connect_timeout(Duration::from_secs(2))
            .timeout(Duration::from_secs(8))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .expect("MCP proxy HTTP client configuration is valid");
        Self {
            client,
            endpoint,
            bearer_token,
            state: Arc::new(Mutex::new(RemoteState::default())),
        }
    }

    /// Returns cached, namespaced tool definitions. A failed refresh never hides the last
    /// successfully discovered tool set.
    pub(crate) async fn tools(&self) -> Vec<Value> {
        let mut state = self.state.lock().await;
        if state.refreshed_at.is_some_and(|at| at.elapsed() < CACHE_TTL) {
            return namespace_tools(&state.tools);
        }
        let previous = state.tools.clone();
        let refreshed = async {
            self.ensure_initialized(&mut state).await?;
            let response = self
                .rpc(&mut state, "tools/list", json!({}))
                .await?;
            let tools = response
                .pointer("/result/tools")
                .and_then(Value::as_array)
                .cloned()
                .ok_or_else(|| "remote tools/list reply has no tools array".to_owned())?;
            if tools.len() > 256 {
                return Err("remote MCP tool count exceeds 256".to_owned());
            }
            Ok::<_, String>(tools)
        }
        .await;
        match refreshed {
            Ok(tools) => {
                state.tools = tools;
                state.refreshed_at = Some(Instant::now());
            }
            Err(_) => {
                // Retry soon when the service is down, but retain a previously working registry.
                state.refreshed_at = None;
            }
        }
        if state.tools.is_empty() && !previous.is_empty() {
            state.tools = previous;
        }
        namespace_tools(&state.tools)
    }

    pub(crate) async fn call(&self, name: &str, arguments: Value) -> Result<Value, String> {
        if name.is_empty() || name.len() > 128 {
            return Err("remote tool name is invalid".to_owned());
        }
        let mut state = self.state.lock().await;
        self.ensure_initialized(&mut state).await?;
        if !state.tools.iter().any(|tool| {
            tool.get("name").and_then(Value::as_str) == Some(name)
        }) {
            let response = self.rpc(&mut state, "tools/list", json!({})).await?;
            state.tools = response
                .pointer("/result/tools")
                .and_then(Value::as_array)
                .cloned()
                .ok_or_else(|| "remote tools/list reply has no tools array".to_owned())?;
            state.refreshed_at = Some(Instant::now());
        }
        if !state.tools.iter().any(|tool| {
            tool.get("name").and_then(Value::as_str) == Some(name)
        }) {
            return Err("remote tool is not currently advertised".to_owned());
        }
        let response = self
            .rpc(
                &mut state,
                "tools/call",
                json!({"name": name, "arguments": arguments}),
            )
            .await?;
        response
            .get("result")
            .cloned()
            .ok_or_else(|| "remote tools/call reply has no result".to_owned())
    }

    async fn ensure_initialized(&self, state: &mut RemoteState) -> Result<(), String> {
        if state.initialized {
            return Ok(());
        }
        let response = self
            .rpc(
                state,
                "initialize",
                json!({
                    "protocolVersion": "2025-03-26",
                    "capabilities": {},
                    "clientInfo": {"name": "droidbridge-mcp-proxy", "version": "0.5.9"}
                }),
            )
            .await?;
        if response.get("result").is_none() {
            return Err("remote MCP initialize reply is invalid".to_owned());
        }
        state.initialized = true;
        // The initialized notification is best-effort; some compatible servers are stateless.
        let mut request = self
            .client
            .post(self.endpoint.clone())
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

    async fn rpc(
        &self,
        state: &mut RemoteState,
        method: &str,
        params: Value,
    ) -> Result<Value, String> {
        state.next_id = state.next_id.saturating_add(1).max(1);
        let payload = json!({
            "jsonrpc": "2.0",
            "id": state.next_id,
            "method": method,
            "params": params
        });
        let mut request = self
            .client
            .post(self.endpoint.clone())
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
        let response = request.send().await.map_err(|_| "remote MCP endpoint is unavailable".to_owned())?;
        if let Some(session) = response.headers().get("MCP-Session-Id").and_then(|value| value.to_str().ok()) {
            state.session_id = Some(session.to_owned());
        }
        if !response.status().is_success() {
            return Err(format!("remote MCP endpoint returned HTTP {}", response.status().as_u16()));
        }
        let content_type = response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or("")
            .to_ascii_lowercase();
        let bytes = response.bytes().await.map_err(|_| "remote MCP response could not be read".to_owned())?;
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
            let message = error.get("message").and_then(Value::as_str).unwrap_or("remote MCP error");
            return Err(message.to_owned());
        }
        Ok(value)
    }
}

fn namespace_tools(tools: &[Value]) -> Vec<Value> {
    tools
        .iter()
        .filter_map(|tool| {
            let name = tool.get("name")?.as_str()?;
            if name.is_empty() || name.len() > 128 {
                return None;
            }
            let mut tool = tool.clone();
            let object = tool.as_object_mut()?;
            object.insert("name".to_owned(), Value::String(format!("{TOOL_PREFIX}{name}")));
            if !object.get("inputSchema").is_some_and(Value::is_object) {
                object.insert(
                    "inputSchema".to_owned(),
                    json!({"type": "object", "properties": {}, "additionalProperties": true}),
                );
            }
            Some(tool)
        })
        .collect()
}

pub(crate) fn is_external_tool(name: &str) -> bool {
    name.starts_with(TOOL_PREFIX) && name.len() > TOOL_PREFIX.len()
}

pub(crate) fn original_tool_name(name: &str) -> &str {
    name.strip_prefix(TOOL_PREFIX).unwrap_or(name)
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn external_tools_are_namespaced_without_mutating_original_definitions() {
        let original = json!({
            "name": "shell",
            "description": "Run a shell command",
            "inputSchema": {"type": "object", "properties": {"command": {"type": "string"}}}
        });
        let namespaced = namespace_tools(std::slice::from_ref(&original));
        assert_eq!(namespaced.len(), 1);
        assert_eq!(namespaced[0]["name"], "mt__shell");
        assert_eq!(namespaced[0]["inputSchema"]["properties"]["command"]["type"], "string");
        assert_eq!(original["name"], "shell");
    }

    #[test]
    fn tools_without_an_object_schema_receive_a_safe_empty_schema() {
        let tool = json!({"name": "status", "description": "Status"});
        let namespaced = namespace_tools(&[tool]);
        assert_eq!(namespaced[0]["name"], "mt__status");
        assert_eq!(namespaced[0]["inputSchema"]["type"], "object");
    }

    #[test]
    fn malformed_remote_tool_definitions_are_ignored() {
        let tools = vec![
            json!({"description": "missing name"}),
            json!({"name": "", "inputSchema": {"type": "object"}}),
            json!({"name": "valid", "inputSchema": {"type": "object"}}),
        ];
        let namespaced = namespace_tools(&tools);
        assert_eq!(namespaced.len(), 1);
        assert_eq!(namespaced[0]["name"], "mt__valid");
    }

    #[test]
    fn only_the_external_namespace_is_dispatched_remotely() {
        assert!(is_external_tool("mt__shell"));
        assert!(!is_external_tool("shell"));
        assert!(!is_external_tool("mt__"));
        assert_eq!(original_tool_name("mt__shell"), "shell");
        assert_eq!(original_tool_name("filesystem"), "filesystem");
    }
}
