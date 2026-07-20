# UUID-First Device Identification

## 问题

蓝牙名称匹配不可靠：单耳机模式或连接瞬间名称未就绪时，Earfree i5 可能被错误识别为 Cambrian。

## 方案

新增 `DeviceProfileRegistry.findByDevice(BluetoothDevice)` 方法：先通过 `BluetoothDevice.getUuids()` 匹配已知 GATT service UUID，匹配失败再回退到名称匹配。所有入口点统一调用此方法。

## 改动

### 1. `DeviceProfileRegistry.findByDevice()`

```kotlin
fun findByDevice(device: BluetoothDevice): DeviceProfile? {
    // Step 1: UUID 匹配（优先，仅对 BLE/GATT 设备有效）
    device.uuids?.forEach { parcelUuid ->
        findByGattServiceUuid(parcelUuid.uuid)?.let { return it }
    }
    // Step 2: 名称匹配（回退）
    val name = device.name ?: device.alias ?: return null
    return findByName(name)
}
```

`device.uuids` 可能返回 null（缓存未就绪），此时静默走回退路径。

### 2. 调用点替换

| 文件 | 行 | 原代码 | 新代码 |
|------|-----|--------|--------|
| `BluetoothProcessHook.kt:137` | `isSupportedDevice` | `findByName(name)` | `findByDevice(device)` |
| `BluetoothProcessHook.kt:150` | `onDeviceConnected` | `findByName(name)` | `findByDevice(device)` |
| `DeviceControlStore.kt` | 各处 | `findByName(device.name)` | `findByDevice(device)` |
| `HeadsetServiceBinderHook.kt` | `isRoseEarphone` | `findByName(name)` | `findByDevice(device)` |
| `MiLinkProcessHook.kt` | `isRoseEarphone` | `findByName(name)` | `findByDevice(device)` |

约 12 处，全部统一。

### 3. 依赖的前提条件

前次实现的 `DeviceProfile.serviceUuid` + `DeviceProfileRegistry.findByGattServiceUuid()` 已可用，本题直接使用。

### 不变事项

- RFCOMM 设备（Cambrian、BudsFeel）的 `serviceUuid` 为 null，不走 UUID 匹配，名称匹配不变。
- `getUuids()` 返回 null 时行为完全等同于改动前。
