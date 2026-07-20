# GATT UUID 设备识别二次验证

## 问题

Earfree i5 单耳机使用时，蓝牙名称在连接瞬间可能未就绪或被误解析，导致 `DeviceProfileRegistry.findByName()` 返回错误的 profile（如 Cambrian）。App 显示的设备类型、功能、颜色因此全错。

## 方案

GATT 服务发现后，用设备实际暴露的 service UUID 比对已知 profile 的 UUID，确认或纠正 profile。RFCOMM 设备（Cambrian, BudsFeel）不受此路径影响。

## 改动

### 1. `DeviceProfile.serviceUuid`

**文件**: `profile/DeviceProfile.kt`

添加属性：

```kotlin
/** BLE GATT service UUID（非 GATT profile 返回 null）。 */
val serviceUuid: java.util.UUID? get() = (transport as? TransportSpec.Gatt)?.serviceUuid
```

`TransportSpec.Gatt.serviceUuid` 已存在且有值，只需要在 `DeviceProfile` 上暴露。

---

### 2. `DeviceProfileRegistry.findByGattServiceUuid()`

**文件**: `profile/DeviceProfileRegistry.kt`

添加方法：

```kotlin
fun findByGattServiceUuid(uuid: java.util.UUID): DeviceProfile? =
    profiles.firstOrNull { uuid == it.serviceUuid }
```

按注册顺序返回第一个匹配的 profile。

---

### 3. `StandaloneGattClient` — 上报匹配结果

**文件**: `service/StandaloneGattClient.kt`

新增 data class 和 StateFlow：

```kotlin
data class ProfileMatchResult(
    val actualProfileId: String,
)

private val _profileMatchResult = MutableStateFlow<ProfileMatchResult?>(null)
val profileMatchResult: StateFlow<ProfileMatchResult?> = _profileMatchResult.asStateFlow()
```

在 `onServicesDiscovered` 中，现有逻辑是 `gatt.getService(gattSpec.serviceUuid)` 找不到则断开。新增逻辑：无论找到与否，遍历 `gatt.getServices()`，用每个 service 的 UUID 调用 `DeviceProfileRegistry.findByGattServiceUuid()`。如果返回的 profile 与当前 `this.profile` 不同，emit `_profileMatchResult`。

注意：emit 后不在此处断连——让 caller 决定。

---

### 4. `DeviceControlStore` — 断开重连

**文件**: `ui/state/DeviceControlStore.kt`

在 `observeDirectGatt` 中订阅 `directGattClient.profileMatchResult`：

```kotlin
directGattClient.profileMatchResult.onEach { result ->
    if (result == null || result.actualProfileId == connectedProfileId) return@onEach
    // UUID 确认了不同的 profile → 断开用正确 profile 重连
    val device = _connectedDevice.value ?: return@onEach
    val newProfile = DeviceProfileRegistry.findById(result.actualProfileId)
    connectedProfileId = result.actualProfileId
    _capabilities.value = newProfile?.capabilities
        ?: DeviceProfileRegistry.defaultProfile.capabilities
    _deviceName.value = newProfile?.displayName ?: _deviceName.value
    disconnect()
    attemptDirectConnect(device, newProfile)
}.launchIn(scope)
```

`attemptDirectConnect` 会自动走重试流程，但这次 profile 已正确。

## 不变事项

- RFCOMM 设备（Cambrian, BudsFeel MK2/Lite）不涉及，不受影响。
- 名称匹配仍是主路径，UUID 仅作为二次确认/纠正。
- 断开重连耗时约 200-500ms，用户在 App 看到短暂断开后恢复，可忽略。

## 文件清单

| 文件 | 改动类型 |
|------|----------|
| `profile/DeviceProfile.kt` | + 1 属性 |
| `profile/DeviceProfileRegistry.kt` | + 1 方法 |
| `service/StandaloneGattClient.kt` | + data class, StateFlow, UUID 比对逻辑 |
| `ui/state/DeviceControlStore.kt` | + profileMatchResult 订阅 + 重连 |
