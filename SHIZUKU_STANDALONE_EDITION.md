# Dedicated Shizuku APK edition

## Product decision

DroidBridge will ship two installable products from the same repository:

| Edition | Install form | Application ID | Privilege provider |
| --- | --- | --- | --- |
| Root edition | Magisk/SukiSU module + companion frontend APK | com.droidbridge.root | Magisk daemon (UID 0) |
| Shizuku edition | Standalone APK; no Magisk module | com.droidbridge.standalone | Shizuku UserService |

The Shizuku edition is not a copy of the Root module and must never require module installation. It installs and runs as an ordinary Android app, starts its own user-space Runtime/MCP host, and connects to Shizuku only for privileged execution. It can coexist with the Root edition because the application IDs are distinct.

## Existing repository state

The repository already has a `:standalone` Android application module with application ID `com.droidbridge.standalone`, its own Runtime foreground service, Shizuku provider, AIDL UserService, execution bridge, native guard, and secure-tunnel/runtime-host code. It is therefore the correct base for the dedicated Shizuku APK; do not create a third duplicate application module.

Important limitation: the current standalone admission policy in `ShizukuContracts.kt` explicitly admits only effective UID 2000. The current standalone Shizuku provider is an ADB-shell execution backend. It is not yet root-equivalent and must not be advertised as such.

## Target user experience

1. Install `DroidBridge-Shizuku.apk` like any normal APK.
2. Install/start Shizuku Manager (or a compatible provider such as Sui).
3. Select the desired Shizuku privilege mode in the app: automatic detection, ADB shell, or root-backed Shizuku where the provider supports it.
4. Grant DroidBridge authorization in Shizuku.
5. DroidBridge binds its UserService and reads the real effective UID from inside that service.
6. The UI clearly reports the actual mode:
   - UID 2000: Shizuku ADB / Shell — useful system access, not root.
   - UID 0: Shizuku Root — root-equivalent process identity, subject to SELinux and Android policy.
   - Any other UID: unsupported identity; execution tools remain unavailable.
7. The app starts its own Runtime, MCP gateway/registry and configured secure tunnel. No Magisk module, Magisk daemon, or module-side frontend is required.

A Shizuku authorization grant alone does not mean root. The app must never infer UID 0 from the selected mode or provider brand.

## Architecture

```text
ChatGPT / Codex / other Agent
  -> authenticated OpenAI Secure Tunnel
  -> Standalone APK: MCP Gateway + Dynamic Registry
  -> Runtime / Task Manager
  -> Execution API
  -> Shizuku Backend Manager
       -> Shizuku UserService (actual UID 2000)
       -> Shizuku UserService (actual UID 0, only when genuinely provided)
  -> Android system
```

The Gateway and Runtime run in the app's own process space and must remain usable for non-privileged MCP services even when Shizuku is stopped. Privileged tools become unavailable immediately when authorization is revoked or the Binder session dies.

## Execution policy

- Preserve the existing general command execution interface for the authenticated owner; do not add a fixed command allowlist as a substitute for the user's explicit full-control requirement.
- Support argv execution and an explicit shell-string operation backed by `/system/bin/sh -c`.
- Keep hard operational bounds: deadlines, output caps, process caps, per-client ownership, cancellation, cleanup on Binder/client death, and session-generation checks.
- Report real UID, backend, SELinux context where available, and capability probe results in every backend status response.
- Do not silently upgrade from UID 2000 to UID 0 or silently downgrade from UID 0 to UID 2000. Backend switching is visible and invalidates old sessions.
- Remote MCP access always requires authentication. No unauthenticated root shell listener is permitted.

## Implementation phases

### S0 — Product separation and packaging
- Keep `:root-frontend` + Magisk module as the Root edition.
- Keep `:standalone` as the dedicated Shizuku APK.
- Add unambiguous artifact names such as `DroidBridge-Root-frontend.apk`, `DroidBridge-Shizuku.apk`, and `droidbridge-root-magisk.zip`.
- Ensure the Shizuku APK build does not depend on Magisk module staging, Magisk daemon binaries, or root frontend installation.
- Give the two editions distinct app labels, icons/branding and release notes while sharing `:ui-common` and compatible runtime components.
- Keep application IDs distinct so both editions can be installed together.

### S1 — Real identity and backend selection
- Refactor the standalone policy from a hard-coded UID-2000 admission check into explicit identity classes.
- Accept UID 2000 only as Shizuku ADB/Shell.
- Accept UID 0 only when the UserService itself reports UID 0 and the root-mode policy's independent checks pass.
- Reject all other identities.
- Add tests for UID spoofing attempts, caller UID mismatch, stale Binder, permission revocation, and backend switching.

### S2 — Root-aware execution provider
- Review every native guard assumption before allowing UID 0. Do not simply change `effectiveUid == 2000` to `effectiveUid == 0 || effectiveUid == 2000`.
- Ensure the native execution guard, filesystem provider, package operations, process lifecycle, ownership and cleanup behave correctly under both identities.
- Add root-specific integration tests: `id`, `whoami`, privileged read/write, process spawn/cancel, stdout/stderr/stdin, timeout, output cap, client death and Binder death.
- Report SELinux denials as permission errors; UID 0 does not bypass enforcing SELinux automatically.

### S3 — Shizuku-only Runtime and tunnel
- Verify Runtime, local MCP facade, dynamic MCP registry, plugin services and OpenAI Secure Tunnel all start without the Magisk module installed.
- Ensure no hidden path invokes the Magisk daemon or assumes module-owned files.
- Verify startup after reboot and recovery after Shizuku service restart.

### S4 — Release and device acceptance
- Build debug and release APKs with reproducible artifact names.
- Verify APK package ID, version, ABI, signer, and SHA-256 before install.
- Install the Shizuku APK alongside the existing Root edition without uninstalling or overwriting it.
- Test both UID 2000 and UID 0 on a compatible provider. Do not label the APK "Root" merely because Shizuku is installed.
- Keep device deployment opt-in; never reboot, replace an installed package with a different signer, or modify Magisk modules without explicit approval and a rollback archive.

## Release naming

Recommended public names:
- DroidBridge Root — Magisk/SukiSU edition
- DroidBridge Shizuku — standalone APK edition

The APK's privilege mode is selected at runtime by the actual Shizuku UserService identity. There is one Shizuku APK, not separate fake-root and shell APKs.
