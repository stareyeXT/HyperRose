# Focus Notification Live Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refresh the focus notification immediately after an ANC mode change and let clicking the focus notification open the HyperRose app.

**Architecture:** Keep the last successfully posted battery and image inputs in the MiBluetooth host process, then repost the same notification without first-float behavior when a valid `ANC_CHANGED` broadcast arrives. Use an explicit app-entry PendingIntent for notification clicks; retain the separate quick-control activity trust boundary for other callers.

**Tech Stack:** Kotlin, Android notifications and PendingIntent, libxposed host hooks, JUnit 4, Gradle, adb/logcat.

## Global Constraints

- Preserve the current first-island-once-per-connection behavior.
- ANC-only refreshes must use `firstFloat = false` and retain all battery and image content.
- Keep launcher validation enabled for the separate quick-control activity.
- Preserve unrelated worktree content, including the untracked `图片/` directory.

---

### Task 1: Refresh the notification after ANC changes

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/MiBluetoothFocusIslandHook.kt`
- Test: `app/src/test/kotlin/com/dohex/hyperrose/hook/MiBluetoothFocusIslandPolicyTest.kt`

**Interfaces:**
- Consumes: trusted `HyperRoseIpc.ANC_CHANGED` broadcasts and the existing `showFocusNotification(...)` path.
- Produces: an updated notification action title and PendingIntent target mode immediately after a valid ANC mode broadcast.

- [x] **Step 1: Confirm the failing event path**

Inspect the `ANC_CHANGED` receiver branch and verify that it only changes `lastKnownAncMode` without calling `showFocusNotification(...)`.

- [x] **Step 2: Cache the complete render state**

Add `lastCaseImageName`, include it in SHOW_ISLAND deduplication, populate it in `rememberIslandState(...)`, and clear it wherever the other cached image names are cleared.

- [x] **Step 3: Repost after a valid ANC change**

Parse `EXTRA_MODE` without replacing a known mode on malformed input. When the mode is valid and a successful island state is cached, call `showFocusNotification(...)` with `lastConnectedDevice`, cached levels and charging flags, and icons resolved from all three cached image names. Log and retain the current notification if reposting fails.

- [ ] **Step 4: Run focused unit tests**

Run: `./gradlew.bat testDebugUnitTest --rerun-tasks`

Expected: existing island-session policy tests and all other debug unit tests pass.

### Task 2: Open the app from notification clicks

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/ipc/HyperRoseIpc.kt`
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/ipc/QuickControlIntentFactory.kt`

**Interfaces:**
- Consumes: the existing explicit PendingIntent builder.
- Produces: focus notification content clicks that launch `AppEntryActivity`.

- [x] **Step 1: Define the app-entry target**

Add `HyperRoseIpc.APP_ENTRY_ACTIVITY` for the exported main activity and use it in a dedicated `QuickControlIntentFactory.createAppLaunchIntent()` helper.

- [x] **Step 2: Use the app-entry PendingIntent**

Build the focus notification `contentIntent` with `createAppLaunchIntent()` and retain the existing background-activity-start options.

- [x] **Step 3: Build the debug APK**

Run: `./gradlew.bat testDebugUnitTest assembleDebug --rerun-tasks`

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists.

### Task 3: Device verification and Git checkpoint

**Files:**
- Verify: `app/build/outputs/apk/debug/app-debug.apk`

**Interfaces:**
- Consumes: connected device `WGKJ6H9HMZOJ495L` through the configured platform-tools adb.
- Produces: runtime evidence for the installed notification and a launchable HyperRose main activity.

- [x] **Step 1: Install and restart hook scopes**

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, then force-stop `com.xiaomi.bluetooth`, `com.android.bluetooth`, and `com.milink.service` so the updated hooks load.

- [x] **Step 2: Verify the installed notification payload**

Inspect `adb shell dumpsys notification --noredact`; the HyperRose notification must contain a `contentIntent` created by `com.xiaomi.bluetooth`, and its focus payload must retain `enableFloat:false`.

- [x] **Step 3: Verify the app entry point**

Launch `com.dohex.hyperrose/.ui.AppEntryActivity` with adb and inspect `dumpsys activity`; the app entry Activity must remain the top resumed Activity. Physical notification tapping remains a device-side confirmation because adb input injection is blocked on this device.

- [x] **Step 4: Commit the focused fix**

Stage only the plan and the three modified Kotlin files, review `git diff --cached`, and commit with `fix(island): open app from focus notification`.
