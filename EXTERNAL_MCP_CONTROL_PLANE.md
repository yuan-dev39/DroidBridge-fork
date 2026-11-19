# External MCP control-plane contract

Status: design contract for the next implementation slice. This document does not claim that the
operations below are implemented by either Runtime host yet.

## Ownership

The UI owns transient form state only. It may receive a credential while a user is editing a
service, but it must not put bearer tokens in DataStore, AppSettings, navigation arguments,
saved state, logs, diagnostics exports, or analytics.

The APK Runtime host must persist credentials in app-private storage protected by an Android
Keystore-backed encryption key. The Magisk daemon may persist credentials in its root-owned
state directory using owner-only permissions and atomic writes. Shizuku must use the storage
owner belonging to the active host; it must not silently fall back to the APK or Magisk store.

The native Runtime receives the complete service snapshot only after a successful durable commit.
A failed commit must leave the previous in-memory registry active. A failed reload must report
the failure and preserve the last known-good registry rather than partially applying services.

## RuntimeConnection operations

The common UI-facing API is intended to expose:

- `externalMcpSettings()`: return a redacted service list, capability/version and active state.
- `replaceExternalMcpServices(services)`: validate and atomically replace the complete list.
- `reloadExternalMcpServices()`: reconcile the running MCP facade with the committed snapshot.

The settings response must never contain bearer-token values. It may include
`has_bearer_token` for each service.

## Root frontend wire operations

The Root frontend and daemon communicate over the authenticated Unix socket protocol already
used by `DaemonConnection`. New operation names are:

- `external_mcp_settings`
- `external_mcp_replace` with `{services_json:"[...]"}`
- `external_mcp_reload` (implemented for the Root daemon; APK/Shizuku hosts remain pending)

The daemon must reject unknown fields and malformed payloads. The replace response is redacted.
The credential is accepted only in the replace request and must not be echoed in the response.

## APK AIDL operations

The APK host must expose the same three operations through `IDroidBridgeRuntime`. The service
must authenticate the Binder caller using its existing `verifyCaller()` guard before forwarding
to the RuntimeHostController. The UI must not use a second, less-restricted Binder service.

## Registry behavior

- Maximum 16 configured services.
- Maximum 64 discovered tools per service and 128 external tools total.
- Tool names are namespaced as `<service>__<tool>`.
- An individual unavailable service does not hide healthy services or native tools.
- A service's enabled flag is applied independently; disabled services are not contacted.
- A failed discovery retains that service's last known-good tool list until a successful refresh
  or explicit deletion.
- Reload is serialized with calls for each service so session state cannot be torn down mid-call.
- Deletion removes the service from discovery immediately after the durable snapshot commits.

## Error contract

Use stable machine-readable codes, not exception text:

- `INVALID_CONFIG`
- `TOO_MANY_SERVICES`
- `DUPLICATE_SERVICE_NAME`
- `CREDENTIAL_STORE_UNAVAILABLE`
- `PERSISTENCE_FAILED`
- `RELOAD_FAILED`
- `RUNTIME_UNAVAILABLE`

Never include endpoint credentials or request headers in error messages.

## Compatibility and rollout

The existing `DROIDBRIDGE_EXTERNAL_MCP_SERVICES` environment variable and legacy MT environment
variables remain a debug-only fallback until all host implementations support the control plane.
Once a persisted user configuration exists, it takes precedence over environment variables.
An explicit persisted empty list disables the fallback.

No release or module deployment should occur until Rust tests, Android unit tests, APK and Root
builds, Shizuku authorization tests, and device-level add/edit/disable/delete/reload tests pass.
