# External MCP registry (debug integration)

This branch adds a multi-server registry to the Rust Runtime MCP facade. External tools are
namespaced as `<service>__<tool>`, for example `mt__shell` or `files__read`. The native
DroidBridge tool names and Contract dispatch are unchanged.

## Temporary debug configuration

The Runtime retains `DROIDBRIDGE_EXTERNAL_MCP_SERVICES` as a debug fallback. The Root daemon now owns a persisted service snapshot and can replace or reload it at runtime. Its initial JSON shape
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
- `enabled`: optional boolean, defaults to `true`; disabled services are not contacted or advertised.
- `bearer_token`: optional Bearer token. It is never added to tool descriptions or tool results.

When the JSON variable is absent, the old single-server variables remain supported:
`DROIDBRIDGE_MT_MCP_URL` and `DROIDBRIDGE_MT_MCP_TOKEN`. An explicit `[]` disables external
servers. Invalid or oversized JSON fails closed (no external servers).

## Network policy and limits

- HTTP is accepted only for loopback, RFC1918 private or link-local IP addresses, plus reserved `localhost` names
  endpoints. Public HTTP is rejected.
- HTTPS endpoints are accepted; URL-embedded usernames/passwords, query strings, fragments,
  redirects and non-`/mcp` paths are rejected.
- Up to 16 servers, 64 tools per server and 128 external tools in the merged catalog.
- Discovery runs concurrently with a four-second per-server deadline. One unavailable server
  does not block healthy servers or the native catalog.
- Per-server clients, sessions, credentials and 15-second tool caches are isolated.
- If a server returns HTTP 404 for an established session, the client clears that session, reinitializes once and retries the original request once; other failures are not blindly retried.
- Remote response bodies are capped at 2 MiB and tool-call arguments are forwarded as JSON objects.

## Localization

The root frontend generates Android's locale configuration from merged resources. English is the
base resource set, Simplified Chinese is in `values-zh-rCN`, and Japanese is in `values-ja`.
Android resource resolution follows the device/app system locale; no extra language selector or
manual locale override is introduced by this change.

## Root daemon control operations

The authenticated Root frontend socket now exposes:

- `external_mcp_settings`: returns service names, endpoint, enabled state and `has_bearer_token`; never returns bearer credentials.
- `external_mcp_replace` with a `services_json` string containing the complete JSON array shown above. The daemon validates first, commits an owner-only atomic settings file, then applies the snapshot.
- `external_mcp_reload`: reloads the last committed snapshot without restarting the listener.

The registry handle is shared by the Root MCP listener and tunnel facade. Replacing an unchanged service reuses its client, MCP session, cache and request-id sequence; removed services stop being advertised and routable. The Root daemon stores the bearer values only in its root-owned settings file with mode 0600. The replace operation returns only an applied flag and service count.

## Remaining work before release

The Root frontend now includes a localized external MCP service manager with add/edit/delete/enable/disable operations. APK AIDL/Keystore integration and JNI hot reload are now implemented in source; Shizuku-backed execution shares the APK Runtime host. Device verification remains. A GitHub Actions workflow checks Rust formatting/tests, shared UI tests, Root frontend Kotlin compilation and standalone APK AIDL/Kotlin compilation. Do not ship this branch as a stable release yet.


Both editions clear legacy per-app locale overrides at startup and expose no manual language picker; the active language follows the device system locale (English fallback for unsupported locales).
