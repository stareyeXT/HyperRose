# GATT UUID Device Identification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Use GATT service UUIDs discovered during BLE connection to verify the `DeviceProfile` matches the actual hardware, and correct it if name-based matching got the wrong profile.

**Architecture:** Add `serviceUuid` accessor on `DeviceProfile`, lookup method on `DeviceProfileRegistry`, GATT service UUID comparison in `StandaloneGattClient.onServicesDiscovered`, and disconnect-reconnect logic in `DeviceControlStore` when UUID reveals a profile mismatch.

**Tech Stack:** Kotlin, Android BluetoothGatt

## Global Constraints

- Existing name-based matching remains the primary path; UUID is only a secondary confirmation/correction.
- RFCOMM profiles (Cambrian, BudsFeel MK2/Lite) are never affected — UUID check only applies to GATT-connected devices.
- Disconnect-reconnect on mismatch targets ~200-500ms UI hiccup; no user-visible error.

---

### Task 1: Add `serviceUuid` property to `DeviceProfile`

**Files:**
- Modify: `profile/DeviceProfile.kt:55` (add property near existing members)

**Interfaces:**
- Produces: `DeviceProfile.serviceUuid: UUID?`

**Details:**

```kotlin
abstract class DeviceProfile(
    val id: String,
    val displayName: String,
    val nameKeywords: List<String>,
    val transport: TransportSpec,
    // ...
) {
    // Already existing members...

    /** BLE GATT service UUID (non-GATT profiles return null). */
    val serviceUuid: java.util.UUID? get() = (transport as? TransportSpec.Gatt)?.serviceUuid
}
```

- [ ] Add the `serviceUuid` property to `DeviceProfile.kt`
- [ ] Build to verify

```bash
cd C:\daima\zwg\HyperRose-master
$env:JAVA_HOME="C:\JAVA"
.\gradlew.bat assembleRelease
# Expected: BUILD SUCCESSFUL
```

---

### Task 2: Add `findByGattServiceUuid()` to `DeviceProfileRegistry`

**Files:**
- Modify: `profile/DeviceProfileRegistry.kt` (add method)

**Interfaces:**
- Consumes: `DeviceProfile.serviceUuid`
- Produces: `DeviceProfileRegistry.findByGattServiceUuid(uuid: UUID): DeviceProfile?`

**Details:**

```kotlin
fun findByGattServiceUuid(uuid: java.util.UUID): DeviceProfile? =
    profiles.firstOrNull { uuid == it.serviceUuid }
```

- [ ] Add the method to `DeviceProfileRegistry.kt`
- [ ] Build to verify

---

### Task 3: Add `ProfileMatchResult` + UUID comparison in `StandaloneGattClient`

**Files:**
- Modify: `service/StandaloneGattClient.kt`

**Interfaces:**
- Consumes: `DeviceProfileRegistry.findByGattServiceUuid()`
- Produces: `StandaloneGattClient.profileMatchResult: StateFlow<ProfileMatchResult?>`

**Details:**

Add near the top of the class:

```kotlin
data class ProfileMatchResult(val actualProfileId: String)
```

Add field alongside other StateFlows:

```kotlin
private val _profileMatchResult = MutableStateFlow<ProfileMatchResult?>(null)
val profileMatchResult: StateFlow<ProfileMatchResult?> = _profileMatchResult.asStateFlow()
```

In `onServicesDiscovered`, after the existing code that finds `writeChar` and enables notifications (around line 230-250), add UUID comparison:

```kotlin
// Check discovered services against all known GATT profiles
val discoveredUuids = gatt.services.map { it.uuid }
for (svcUuid in discoveredUuids) {
    val matchedProfile = DeviceProfileRegistry.findByGattServiceUuid(svcUuid)
    if (matchedProfile != null && matchedProfile.id != profile.id) {
        Log.i(TAG, "GATT service UUID ${svcUuid} matches profile ${matchedProfile.id} " +
            "(current: ${profile.id}), correcting")
        _profileMatchResult.value = ProfileMatchResult(matchedProfile.id)
        break
    }
}
```

- [ ] Add `ProfileMatchResult` data class, StateFlow, and UUID comparison logic
- [ ] Add import for `DeviceProfileRegistry`
- [ ] Build to verify

---

### Task 4: Wire profile correction in `DeviceControlStore`

**Files:**
- Modify: `ui/state/DeviceControlStore.kt` (in `observeDirectGatt`)

**Interfaces:**
- Consumes: `StandaloneGattClient.profileMatchResult`

**Details:**

In `observeDirectGatt()`, after the existing `directGattClient.battery` observer, add:

```kotlin
directGattClient.profileMatchResult.onEach { result ->
    if (result == null || result.actualProfileId == connectedProfileId) return@onEach
    val device = _connectedDevice.value ?: return@onEach
    val newProfile = com.dohex.hyperrose.profile.DeviceProfileRegistry.findById(result.actualProfileId)
    connectedProfileId = result.actualProfileId
    _capabilities.value = newProfile?.capabilities
        ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.capabilities
    newProfile?.let { _deviceName.value = it.displayName }
    directGattClient.disconnect()
    // Use attemptDirectConnect so retry/fallback still applies
    attemptDirectConnect(device, newProfile)
}.launchIn(scope)
```

- [ ] Add the profileMatchResult subscription
- [ ] Build to verify

---

### Task 5: Build & verify

- [ ] Run full release build

```bash
cd C:\daima\zwg\HyperRose-master
$env:JAVA_HOME="C:\JAVA"
.\gradlew.bat assembleRelease
# Expected: BUILD SUCCESSFUL
```

- [ ] Commit

```bash
git add -A
git commit -m "feat: verify device profile via GATT service UUID, correct on mismatch"
```
