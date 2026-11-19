# Shizuku bridge in the Root edition

The Root edition now includes the official Shizuku API/provider and a narrow UserService identity handshake. The capability row distinguishes manager installation, service availability, authorization, connection, and the actual UID returned by the remote UserService.

The probe accepts UID 2000 (ADB shell) and UID 0 (root/Sui). It deliberately exposes no arbitrary command or filesystem API.

**Important boundary:** this stage does not switch the Rust Magisk daemon to Shizuku. The Root edition's Runtime, persistence, and MCP ingress still belong to the Magisk module. The standalone edition already contains the broader Shizuku execution provider; the next migration stage is to port its guarded execution bridge into the Root Runtime and add a Shizuku-hosted Runtime lifecycle. Until that work and device tests are complete, this is a verified connection/identity probe, not a claim that the Root Runtime can run without Magisk.

Reference implementation: standalone/src/main/java/com/droidbridge/standalone/execution/shizuku/.
