# Dynamic MCP Registry for DroidBridge Root

Status: implementation design for `feature/dynamic-mcp-registry`.

## Goal

Keep DroidBridge's native MCP tools and the existing OpenAI Secure Tunnel unchanged at the transport boundary, while allowing the root daemon to register additional MCP servers. Both the loopback listener and the Secure Tunnel must use the same facade and therefore expose the same merged tool catalog.

## Existing architecture findings

- `McpFacade` owns the static native tool catalog and validates native tool calls against generated `PublicPayload` contracts.
- Both `McpListener` and `TunnelClient` serve requests through `McpFacade`; the tunnel does not expose a separate tool implementation.
- The root daemon owns the persistent settings store and creates the listener and tunnel in `DaemonIngress::reconcile_*`.
- The frontend communicates with the daemon over an authenticated Unix-domain socket using named operations.
- Root settings are stored in the daemon-only state directory with restrictive file permissions. The frontend must never receive API secrets except when explicitly revealing the existing MCP token; dynamic server credentials should remain daemon-owned.
- The Android root frontend exposes daemon operations through `DaemonConnection` and product UI components.

## Proposed architecture

1. **Registry model (runtime crate)**
   - A cloneable shared registry, passed to each facade created by the daemon.
   - A service has a stable ID, display name, endpoint, enabled flag, optional bearer credential, last refresh result, and discovered tools.
   - The registry keeps service configuration separate from ephemeral MCP session state.
   - Service IDs and tool names are normalized and bounded. External tool names are published as `<service_id>__<tool_name>`; native names remain unchanged.
   - Duplicate published names, invalid schemas, and invalid endpoints are rejected.

2. **Transport**
   - Start with Streamable HTTP MCP. Perform `initialize`, retain the negotiated protocol version and optional `Mcp-Session-Id`, send `notifications/initialized`, then use `tools/list` and `tools/call`.
   - Do not follow redirects. Bound connect/overall timeouts, response bytes, tool count, and concurrent calls.
   - Never log Authorization headers or credentials.
   - Default endpoint policy: loopback or explicitly approved private LAN IPv4 endpoints only. Reject userinfo, fragments, query credentials, non-HTTP schemes, public IPs, and DNS names until a deliberate allow-list design exists. This prevents the root daemon becoming an SSRF proxy.
   - A failing service is isolated: native tools and other registered servers remain available.

3. **Facade integration**
   - Add external tool definitions to `tools/list` without altering native schemas.
   - Route namespaced `tools/call` to the owning service and return the upstream MCP result in the facade's existing JSON-RPC result envelope.
   - Preserve upstream `isError`, text, image, and structured content where supported; cap response size.
   - Both the local listener and OpenAI Secure Tunnel receive the same shared registry object. Adding/removing a server must not restart or recreate the tunnel.

4. **Daemon control plane and persistence**
   - Add daemon operations: `mcp_servers_list`, `mcp_server_add`, `mcp_server_remove`, `mcp_server_set_enabled`, `mcp_server_refresh`, and `mcp_server_test`.
   - Persist service metadata and secrets only in root-only settings files (0600); redact secrets from list/status responses.
   - Commit configuration atomically before publishing it to the shared registry.
   - Validate endpoint and perform a discovery test before enabling a newly added service.

5. **Root frontend**
   - Add a dedicated MCP Servers settings section.
   - Support add, remove, enable/disable, refresh, test connection, and display discovered tool counts/status.
   - Secret fields are write-only; do not populate them from daemon responses.
   - Explain that changes apply to the existing tunnel automatically.

## Security and reliability requirements

- No arbitrary public URL proxying by default.
- No redirects, DNS rebinding, URL userinfo, or credential-bearing query strings.
- Secret-free logs and status responses.
- Atomic root-only persistence.
- Per-service timeouts, bounded response sizes, bounded tools, and concurrency limits.
- One unavailable service cannot prevent the native catalog or other services from loading.
- Do not restart the Secure Tunnel when the registry changes.
- Keep existing native tool names, schemas, and behavior byte-for-byte compatible.

## Acceptance tests

- Native tools remain present and unchanged with an empty registry.
- Enabled service tools appear with stable namespaced names; disabled services do not.
- Duplicate service IDs/tool names and invalid schemas are rejected.
- Namespaced calls route to the correct endpoint and preserve arguments.
- Upstream errors, timeouts, malformed JSON, oversized responses, and disconnected services are isolated.
- Private-network allow-list and redirect restrictions are enforced.
- Secrets never appear in list/status/errors/log output.
- Updating the registry is visible to both listener and tunnel without restarting either.
- Android frontend add/remove/toggle/refresh/test flows work through the authenticated daemon link.
- Rust workspace tests, formatting, Android frontend tests, and Android arm64 release build pass before deployment.

## Deployment gate

No device files are changed until the branch has passing automated checks and a release artifact is reviewed. Before any Pad 2 deployment, back up the existing module, daemon binary, and root-only settings, then stage a reversible test deployment. The currently installed Pad 2 module and Secure Tunnel remain untouched during development.
