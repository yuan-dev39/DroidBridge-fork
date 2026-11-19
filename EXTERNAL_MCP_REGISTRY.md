# External MCP registry (debug integration)

This branch adds a multi-server registry to the Rust Runtime MCP facade. External tools are
namespaced as `<service>__<tool>`, for example `mt__shell` or `files__read`. The native
DroidBridge tool names and Contract dispatch are unchanged.

## Temporary debug configuration

The Runtime reads `DROIDBRIDGE_EXTERNAL_MCP_SERVICES` when its MCP facade is created. Its value
is a JSON array:

```json
[
  {
    "name": "mt",
    "endpoint": "http://127.0.0.1:8787/mcp",
    "bearer_token": ""
  },
  {
    "name": "files",
    "endpoint": "http://192.168.1.20:9000/mcp",
    "bearer_token": "replace-with-a-secret"
  },
  {
    "name": "cloud",
    "endpoint": "https://mcp.example.com/mcp",
    "bearer_token": "replace-with-a-secret"
  }
]
```

Fields:
- `name`: unique lowercase service id (letters, digits, underscore and hyphen; up to 24 chars).
- `endpoint`: Streamable HTTP MCP endpoint. The path must be exactly `/mcp`.
- `bearer_token`: optional Bearer token. It is never added to tool descriptions or tool results.

When the JSON variable is absent, the old single-server variables remain supported:
`DROIDBRIDGE_MT_MCP_URL` and `DROIDBRIDGE_MT_MCP_TOKEN`. An explicit `[]` disables external
servers. Invalid or oversized JSON fails closed (no external servers).

## Network policy and limits

- HTTP is accepted only for loopback, RFC1918 private, link-local, `.local` or `.localhost`
  endpoints. Public HTTP is rejected.
- HTTPS endpoints are accepted; URL-embedded usernames/passwords, query strings, fragments,
  redirects and non-`/mcp` paths are rejected.
- Up to 16 servers, 64 tools per server and 128 external tools in the merged catalog.
- Discovery runs concurrently with a four-second per-server deadline. One unavailable server
  does not block healthy servers or the native catalog.
- Per-server clients, sessions, credentials and 15-second tool caches are isolated.
- Remote response bodies are capped at 2 MiB and tool-call arguments are forwarded as JSON objects.

## Localization

The root frontend generates Android's locale configuration from merged resources. English is the
base resource set, Simplified Chinese is in `values-zh-rCN`, and Japanese is in `values-ja`.
Android resource resolution follows the device/app system locale; no extra language selector or
manual locale override is introduced by this change.

## Remaining work before release

The environment-variable format is only a debug integration seam. A user-facing service manager,
secure credential persistence, live configuration reload, reconnect controls, build/test validation
and real-device verification are still required. Do not ship this branch as a stable release yet.
