# UUID-First Device Identification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `DeviceProfileRegistry.findByDevice(BluetoothDevice)` try GATT service UUID matching first, then fall back to name matching, and replace all call sites.

**Architecture:** Single new method `findByDevice()` on `DeviceProfileRegistry`; replace ~12 existing `findByName(name)` calls with `findByDevice(device)` across hook and app processes.

**Tech Stack:** Kotlin, Android BluetoothDevice API

## Global Constraints

- Pre-requisite: `DeviceProfile.serviceUuid` and `DeviceProfileRegistry.findByGattServiceUuid()` already exist from prior implementation.
- RFCOMM profiles (Cambrian, BudsFeel) have `serviceUuid = null`, unaffected.
- `device.uuids` may be null → silent name fallback.

---

### Task 1: Add `findByDevice()` to `DeviceProfileRegistry`

**File:**
- Modify: `profile/DeviceProfileRegistry.kt`

**Interfaces:**
- Consumes: `DeviceProfile.serviceUuid`, `DeviceProfileRegistry.findByGattServiceUuid(uuid)`, `DeviceProfileRegistry.findByName(name)`
- Produces: `DeviceProfileRegistry.findByDevice(device: BluetoothDevice): DeviceProfile?`

Add this method to the `DeviceProfileRegistry` object:

```kotlin
import android.bluetooth.BluetoothDevice

fun findByDevice(device: BluetoothDevice): DeviceProfile? {
    device.uuids?.forEach { parcelUuid ->
        findByGattServiceUuid(parcelUuid.uuid)?.let { return it }
    }
    val name = device.name ?: device.alias ?: return null
    return findByName(name)
}
```

Read the file first; insert after `findById`.

- [ ] Implement `findByDevice()`
- [ ] Build to verify: `.\gradlew.bat assembleRelease`

---

### Task 2: Replace call sites in hooks (BluetoothProcessHook)

**File:**
- Modify: `hook/BluetoothProcessHook.kt` (2 call sites)

Replace both:

```kotlin
// Line ~137 in isSupportedDevice(device):
// Old: val name = device.name ?: device.alias; if (name != null && DeviceProfileRegistry.findByName(name) != null)
// New:
if (DeviceProfileRegistry.findByDevice(device) != null) return true

// Line ~150 in onDeviceConnected:
// Old: val profile = (device.name ?: device.alias)?.let { DeviceProfileRegistry.findByName(it) }
// New:
val profile = DeviceProfileRegistry.findByDevice(device)
```

- [ ] Edit BluetoothProcessHook.kt
- [ ] Build to verify: `.\gradlew.bat assembleRelease`

---

### Task 3: Replace call sites in HeadsetServiceBinderHook + MiLinkProcessHook

**Files:**
- Modify: `hook/HeadsetServiceBinderHook.kt`
- Modify: `hook/MiLinkProcessHook.kt`

In each file, find the pattern:
```kotlin
DeviceProfileRegistry.findByName(name) != null
```
and replace with:
```kotlin
DeviceProfileRegistry.findByDevice(device) != null
```

For `MiLinkProcessHook.kt`, the method signature may take a `BluetoothDevice` directly. If it takes a name string, leave as-is (can't get UUIDs from a name alone).

- [ ] Edit HeadsetServiceBinderHook.kt
- [ ] Edit MiLinkProcessHook.kt  
- [ ] Build to verify: `.\gradlew.bat assembleRelease`

---

### Task 4: Replace call sites in DeviceControlStore

**File:**
- Modify: `ui/state/DeviceControlStore.kt`

Find all patterns like:
```kotlin
DeviceProfileRegistry.findByName(device.name ?: "")
DeviceProfileRegistry.findByName(bonded.name ?: "")
DeviceProfileRegistry.findByName(it)
```

Where `device`/`bonded`/`it` is a `BluetoothDevice`, replace with:
```kotlin
DeviceProfileRegistry.findByDevice(device)
DeviceProfileRegistry.findByDevice(bonded)
DeviceProfileRegistry.findByDevice(it)
```

Where the argument is a `String` (name only, no device available), leave as `findByName()`.

There are approximately 8 call sites in this file.

- [ ] Replace all applicable call sites in DeviceControlStore.kt
- [ ] Build to verify: `.\gradlew.bat assembleRelease`

---

### Task 5: Build + Commit

- [ ] Run full release build
```bash
cd C:\daima\zwg\HyperRose-master
$env:JAVA_HOME="C:\JAVA"
.\gradlew.bat assembleRelease
```
- [ ] Commit
```bash
git add -A
git commit -m "feat: UUID-first device identification via findByDevice()"
git push origin master
```
