# SonyPods System Integration Implementation Plan

> **For agentic workers:** Execute the tasks inline with focused verification after each task.

**Goal:** Complete the SonyPods-inspired HyperOS integration for battery/focus-island display, AOD battery text, notification ANC cycling, notification-click control popup, and connection-time popup.

**Architecture:** Keep the existing Bluetooth -> MiBluetooth broadcast bridge and `QuickControlActivity`. Extend the MiBluetooth notification hook to build action intents that route through the existing validated ANC command bridge, and use the same activity pending intent for connection and notification clicks. Keep state de-duplication and disconnect cleanup in the existing hook.

**Tech Stack:** Kotlin, Android notifications, Xposed hooks, `focus-api` FocusNotification V3, existing HyperRose IPC broadcasts and Compose popup activity.

## Global Constraints

- Preserve existing sender validation for all exported broadcasts and activity launches.
- Keep compatibility with the current `focus-api` 1.4 dependency and Android API 35+.
- Do not introduce a second device-control transport or duplicate popup UI.

### Task 1: Notification ANC cycle action

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/MiBluetoothFocusIslandHook.kt`

**Interfaces:**
- Consumes existing `SET_ANC`/`ANC_SELECT` IPC constants and the current notification device/address extras.
- Produces a notification action that cycles Off -> Noise Cancel -> Transparent and sends the selected mode through the existing Bluetooth command receiver.

- [x] Add an action `PendingIntent` using `HyperRoseIpc.ANC_SELECT`, carrying device address and the next mode.
- [x] Add the action to both the first island notification and subsequent focus notifications.
- [x] Keep the action label stable and derive the next mode from the last known ANC mode when available, falling back to Noise Cancel.

### Task 2: Connection popup

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/MiBluetoothFocusIslandHook.kt`

**Interfaces:**
- Consumes existing `DEVICE_CONNECTED` broadcasts and `QuickControlIntentFactory`.
- Produces an immediate heads-up notification/focus island whose content intent launches `QuickControlActivity`.

- [x] Cache the connected device from `DEVICE_CONNECTED` and post a connection notification with the same popup pending intent.
- [x] Ensure the first battery update replaces the connection notification without creating duplicate notification IDs.
- [x] Clear cached connection data on disconnect and cancel the notification.

### Task 3: Verification and regression coverage

**Files:**
- Modify: `app/src/test/kotlin/com/dohex/hyperrose/ipc/BroadcastSenderValidatorTest.kt` only if new intent validation needs coverage.
- Modify: `app/src/test/kotlin/com/dohex/hyperrose/model/BatteryStateTest.kt` only if mode/state fallback helpers are extracted.

- [x] Run unit tests.
- [x] Run `assembleDebug` and inspect compiler output for notification API/Xposed issues.
- [x] Review the final diff for unrelated changes and preserve the existing untracked image assets.
