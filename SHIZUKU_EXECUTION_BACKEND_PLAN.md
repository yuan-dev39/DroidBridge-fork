# Shizuku execution backend for the Root edition

## Current state (do not overstate)

The Root edition currently has only a Shizuku discovery, authorization, UserService bind, and UID identity probe. It does not expose shell execution, filesystem operations, or a Shizuku-hosted MCP runtime. The Magisk daemon remains the active Runtime and MCP host.

The standalone edition contains a much broader Shizuku provider (AIDL contracts, process lifecycle, filesystem operations, ownership tracking, cancellation, deadlines, and a native execution guard). However, its current admission policy explicitly requires effective UID 2000. It is therefore an ADB-shell backend, not a root-equivalent backend. It must not be copied into the Root edition and relabeled as root.

## Permission model

DroidBridge must report the real effective UID returned by the UserService:

| Backend | Expected effective UID | Permission semantics |
| --- | ---: | --- |
| Magisk Root | 0 | Root daemon, subject to SELinux and Android policy |
| Shizuku ADB | 2000 | ADB shell privileges; not root |
| Shizuku Root / Sui | 0 | Root only when the actual UserService process reports UID 0 |

A Shizuku authorization grant alone does not imply root. ADB-started Shizuku normally runs its UserService as UID 2000. The app must never infer UID 0 from a button selection, manager brand, or authorization state.

## Required end-state

The user-facing goal is arbitrary command execution with root-equivalent authority when (and only when) the selected backend actually runs as UID 0. Do not impose a fixed command allowlist on the user's own authenticated execution channel. Keep the command interface general: program + argv, plus an explicitly documented shell-string mode using /system/bin/sh -c.

The execution provider must support:

- exec: program, argv, environment, working directory, stdin, stdout, stderr, exit code.
- Managed long-running processes: start, poll/read output, write stdin, signal/terminate, and wait.
- Bounded execution deadlines, output limits, process-count limits, and reliable cleanup on client death or Shizuku binder loss.
- Filesystem read/write/list/stat operations, with descriptor-based transfer for large files.
- Android service/package/system APIs where supported by the effective UID and Android version.
- Structured error codes distinguishing permission denial, unsupported operation, timeout, cancellation, stale authority, and I/O failure.
- Capability reporting based on the actual UID and runtime probes, not on the selected label.

## Architecture migration

1. Backend contract: define a shared execution contract used by Magisk Root, Shizuku ADB, and Shizuku Root. Each request carries a backend generation/session identity so results from a revoked or replaced Shizuku session cannot be accepted.
2. Shizuku UserService: port/adapt the standalone AIDL service and process/filesystem executors into the Root edition. The service must validate the calling app UID, bind client tokens to Binder death, own process handles per client, and reject stale sessions.
3. Root-aware policy: retain UID 2000 support, add UID 0 only after a separate threat-model review and tests. Do not weaken the standalone shell guard's UID-2000 assumptions in place. Root execution must still use safe descriptor handling, bounded queues, deadlines, and cleanup.
4. Frontend-to-Runtime bridge: the Shizuku UserService is authorized for the app UID, not for the Magisk daemon UID. The frontend must broker execution requests to the UserService over an authenticated, versioned IPC channel. Do not call Shizuku directly from the root daemon as though it were the authorized app.
5. MCP tools: expose system.exec, system.process_*, and system.fs_* through the existing MCP registry. Namespace and backend identity must be explicit in tool metadata and results.
6. Shizuku-only host: if the Magisk module is absent, the app must still start a user-space Runtime/MCP host under the selected Shizuku identity and bring up the configured secure tunnel. A green Shizuku row alone is not proof that this path works.
7. Backend manager: add explicit selection and optional fallback order. Fallback must never silently upgrade authority or silently downgrade from UID 0 to UID 2000. Every switch invalidates old sessions and reports the new real UID.
8. Security boundary: arbitrary commands are allowed for the authenticated owner, but remote ingress must require a strong per-install bearer credential, private/local binding by default, explicit tunnel enablement, and credential redaction. Never expose an unauthenticated shell listener. Keep timeout/output/process limits and an audit option.

## Acceptance tests before calling this complete

- Shizuku ADB: actual UID 2000; arbitrary user-selected commands work; root-only paths/operations fail honestly with permission errors.
- Shizuku Root/Sui: actual UID 0; id, whoami, privileged read/write, and process cancellation are verified from inside the UserService.
- Revoke Shizuku permission or kill its binder during a running process: the process is cleaned up, the session is invalidated, and no stale result is accepted.
- Exercise stdout/stderr separately, stdin, non-zero exit status, timeout, output cap, process cap, client death, and large-file descriptor transfer.
- Run the MCP tools through the local facade and through the authenticated OpenAI Secure Tunnel.
- Disable/uninstall the Magisk module and verify Shizuku-only Runtime + MCP + tunnel still work.
- Verify backend switching and restart persistence, and verify no device state is changed by build/deploy tests without a backup and signature check.

Until all acceptance tests pass, label this work in progress. The current Root-edition Shizuku capability is an identity probe only.

## Existing implementation references

- Root edition probe: root-frontend/src/main/java/com/droidbridge/root/ShizukuBackendController.kt
- Standalone provider: standalone/src/main/java/com/droidbridge/standalone/execution/shizuku/
- Standalone's current UID admission policy: ShizukuLaunchPolicy.isAdmitted in ShizukuContracts.kt (UID 2000 only).
