# Project Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the security, connection-state, protocol parsing, lifecycle, signing, and CI defects found during the project review.

**Architecture:** Keep the existing broadcast-based bridge but authenticate every exported receiver from the sender UID. Make transport readiness the single source of truth for connection state, centralize refresh handling, harden packet parsing at the protocol boundary, and reject stale GATT callbacks.

**Tech Stack:** Kotlin 2.4, Android API 35+, Android Bluetooth APIs, JUnit 4, Gradle Kotlin DSL, GitHub Actions.

## Global Constraints

- Preserve the user's existing uncommitted changes.
- Add no new runtime dependencies.
- Keep cross-process behavior compatible with `com.android.bluetooth`, `com.xiaomi.bluetooth`, and `com.milink.service`.
- Do not commit changes unless explicitly requested.

---

### Task 1: Authenticate exported broadcasts

**Files:**
- Create: `app/src/main/kotlin/com/dohex/hyperrose/ipc/BroadcastSenderValidator.kt`
- Modify: exported receiver registrations and handlers under `hook/`, `ipc/`, `ui/screen/`, and `ui/state/`
- Test: `app/src/test/kotlin/com/dohex/hyperrose/ipc/BroadcastSenderValidatorTest.kt`

**Interfaces:**
- Produces: `BroadcastSenderValidator.isAllowed(packageManager, sentFromUid, allowedPackages): Boolean`

- [ ] Add unit tests for matching UID packages, unknown UIDs, and disallowed packages.
- [ ] Implement a package-manager based UID validator.
- [ ] Reject untrusted senders at the start of every exported custom-action receiver.
- [ ] Use `RECEIVER_NOT_EXPORTED` where the receiver has no legitimate cross-package sender.
- [ ] Run the focused validator tests.

### Task 2: Report connection state only after transport readiness

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/BluetoothProcessHook.kt`
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/GattDeviceSession.kt`
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/DeviceSession.kt`

**Interfaces:**
- Consumes: existing `DeviceSession.broadcastDeviceConnected()`
- Produces: one `DEVICE_CONNECTED` event after GATT or RFCOMM is usable

- [ ] Remove the eager `DEVICE_CONNECTED` broadcast from the A2DP callback.
- [ ] Broadcast GATT readiness after notification setup succeeds.
- [ ] Clean up and report unusable GATT sessions on discovery/setup failure.
- [ ] Compile the app.

### Task 3: Harden protocol parsing and lifecycle state

**Files:**
- Modify: the three TLV response parsers
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/service/StandaloneGattClient.kt`
- Modify: refresh handling in `DeviceSession.kt` and `BluetoothProcessHook.kt`
- Test: protocol parser tests

**Interfaces:**
- Produces: parsers that return `Unknown` or skip malformed TLVs without throwing

- [ ] Add regression tests for truncated ANC/game/battery TLVs and unknown battery levels.
- [ ] Enforce whole-TLV and per-type value bounds.
- [ ] Ignore stale `BluetoothGatt` callbacks and clear current GATT fields on disconnect.
- [ ] Route `REFRESH_STATUS` through only one receiver.
- [ ] Run all unit tests.

### Task 4: Fix notification state and Android lint errors

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/MiBluetoothFocusIslandHook.kt`
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/profile/DeviceProfileRegistry.kt`

**Interfaces:**
- Produces: notification display helpers that report success and permission-safe device matching

- [ ] Mark the first island as shown only after notification delivery succeeds.
- [ ] Handle notification permission/security failures in the hooked host process.
- [ ] Make Bluetooth device profile lookup tolerate revoked permissions.
- [ ] Run `lintDebug` and resolve remaining errors introduced by these paths.

### Task 5: Enforce release and CI quality gates

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: release builds that cannot silently use the Android debug certificate

- [ ] Remove automatic debug-keystore release signing.
- [ ] Fail release packaging when signing environment variables are incomplete.
- [ ] Add unit tests and lint to CI before APK assembly.
- [ ] Run unit tests, lint, debug build, and signed/unsigned release configuration checks.

### Task 6: Final verification

**Files:**
- Verify all modified files; do not modify unrelated user files.

- [ ] Run `git diff --check`.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --rerun-tasks`.
- [ ] Run `./gradlew.bat :app:lintDebug`.
- [ ] Run `./gradlew.bat :app:assembleDebug`.
- [ ] Inspect the final diff and report residual device-only validation risks.
