# Shizuku bridge in the Root edition

The Root edition currently includes the official Shizuku API/provider and a narrow UserService identity handshake. It reports connection and the actual UID returned by the remote UserService. It does not yet expose shell execution, filesystem operations, or a Shizuku-hosted MCP runtime. The Magisk daemon remains the active Runtime and MCP host.

**Permission distinction:** Shizuku started through ADB normally runs its UserService as UID 2000 (shell), which is not root. A Shizuku installation started through a root-capable path may run as UID 0, but DroidBridge must verify the actual UserService UID rather than infer it from the manager or authorization state. The standalone edition's existing provider is currently restricted to UID 2000 and is not itself a root backend.

The complete migration requirements, backend architecture, security boundary, and device acceptance tests are documented in SHIZUKU_EXECUTION_BACKEND_PLAN.md. Until those tests pass, this feature must be described as an identity probe only—not a complete Shizuku execution backend.
