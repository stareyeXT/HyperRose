# ROSE CAMBRIAN 整机电量（Aggregate）设计

日期：2026-08-13
参考：HyperEars Edifier W860NB PRO 的 `BatteryState.Aggregate(percent)` 整机单值模式

## 背景

HyperRose 中 ROSE CAMBRIAN 是**整机形态**耳机（README 电量显示列 = "耳机"）。它的电量回包
（type `0x04` / sub `0x0C` 单字节路径，以及 status `0x0C` TLV 三值里只有一个非零值时）只携带一个
单字节整机值。当前实现把这个单值塞进 `TwsBatteryState.left` 槽，依赖 `DeviceSession` 的
`isMono` 兜底逻辑显示，语义不准确：整机值被伪装成左耳。

HyperEars 的做法是用独立的 `BatteryState.Aggregate(percent)` 表示整机单值，解析严格校验单字节
payload `0..100`，不伪装成左右耳组件。本设计把该模式移植到 HyperRose 的 CAMBRIAN 电量全链路
（解析 + 模型 + IPC + 显示）。

## 设计

### 1. 模型层 `app/src/main/kotlin/com/dohex/hyperrose/model/BatteryState.kt`

`TwsBatteryState` 新增可选字段：

```kotlin
data class TwsBatteryState(
    val left: EarBatteryState? = null,
    val right: EarBatteryState? = null,
    val caseBattery: Int? = null,
    val overall: Int? = null,   // 整机单值（头戴/整机形态），0..100
)
```

- 向后兼容：新增默认参数不影响既有调用。
- 语义约束：`overall` 与 left/right/case 互斥——整机形态不产生组件电量，组件形态不产生整体电量。
  由解析器与投影方共同遵守，模型不强制（避免过度校验）。

### 2. IPC 层 `app/src/main/kotlin/com/dohex/hyperrose/ipc/HyperRoseIpc.kt`

新增常量：

```kotlin
const val EXTRA_OVERALL_LEVEL = "$EXTRA_PREFIX.overall_level"
```

用于 BATTERY_CHANGED / DEVICE_CONNECTED 广播携带整机单值，使 App 端（DeviceControlStore）能还原
`overall`，不再依赖 left 槽。

### 3. 解析层 `app/src/main/kotlin/com/dohex/hyperrose/profile/rose_cambrian/RoseCambrianResponseParser.kt`

两条单字节整机值路径改为返回 `overall`，不再塞 left：

- **`parseType4Response`（type `0x04` / sub `0x0C`）**：单值路径返回
  `TwsBatteryState(overall = level)`，`level = values.firstOrNull()?.asBatteryLevelOrNull()`，
  非法（`0xFF` / 越界）返回 `DeviceResponse.Unknown`。
- **`parseStatusResponse`（status `0x0C` TLV）**：`nonZero.size == 1 && values.size >= 3` 分支
  （三个字节中仅一个真实电量）返回 `TwsBatteryState(overall = nonZero[0])`，不再伪装 left。

其余分支（2 值 / 3 值组件电量）保持不变，继续走 left/right/case。

### 4. 显示 / 广播层

**4a. `DeviceSession.handleResponse`（BATTERY_CHANGED 广播）**

当前代码（DeviceSession.kt:71-84）只透传 left/right/case。改为：`overall` 非空时追加
`EXTRA_OVERALL_LEVEL = overall`，同时保持 `EXTRA_LEFT_LEVEL`/`EXTRA_RIGHT_LEVEL`/`EXTRA_CASE_LEVEL`
均填 `-1`（整机形态没有组件电量，避免伪造 L/R/C）。

**4b. `DeviceSession.handleResponse`（SHOW_ISLAND 广播）**

当前 `isMono = battery.right == null && battery.caseBattery == null` 依赖 left 槽。改为兼容 `overall`：
当 `battery.overall != null` 时视同整机单值模式——`EXTRA_LEFT_LEVEL = -1`、`EXTRA_RIGHT_LEVEL = -1`、
`EXTRA_CASE_LEVEL = overall`。这触发现有 `FocusIslandBridge.isMonoCase` 单值渲染（左右同值/单一
"60%"），复用现有路径，不新增整体渲染分支。

**4c. `BluetoothProcessHook`（DEVICE_COLOR_CHANGED → 重发 SHOW_ISLAND，同上 isMono 逻辑）**

同一 `isMono`/left-slot 用法（BluetoothProcessHook.kt:317-327）同步改为识别 `overall`，逻辑与 4b 一致：
`overall != null` 时 case 填 overall、左右填 -1。建议抽取一个小工具函数
`TwsBatteryState.overallOrMonoCase()` 或直接在两个调用点引用 `overall` 字段，避免第三处复制。

**4d. `MiLinkProcessHook`（BATTERY_CHANGED → `buildMiLinkBatteryList`）**

- onReceive 读取 `EXTRA_OVERALL_LEVEL`：存在时 `currentLeftBattery = currentRightBattery = overall`、
  `currentCaseBattery = -1`（对齐 HyperEars `fromAggregate`：左右同值、盒不可用）。
- `buildMiLinkBatteryList()` 无需改动：`[case(-1), left(X), right(X), 0, 0, 0]` 即整机单块电量。

**4e. `HeadsetServiceBinderHook.parseBatteryFromExtras`**

读取 `EXTRA_OVERALL_LEVEL`，存在时返回 `TwsBatteryState(overall = level)`，不还原成 left/right。

**4f. App 端 `DeviceControlStore.parseBattery`**

读取 `EXTRA_OVERALL_LEVEL`：存在时返回 `TwsBatteryState(overall = level)`（保持现有非零单值折叠
逻辑不变，两者互斥）。

**4g. 应用内 UI**

- `BatteryCard`：`overall` 非空时渲染单个"整机 XX%"（新 `BatteryCell(label = "整机")`），
  不渲染 L/R/C 格子。
- `DeviceDetailPage` 预设 / `QuickControlActivity` preset 构造不动（overall 不参与 preset）。

### 5. 直连客户端（StandaloneGattClient / StandaloneRfcommClient）

两块客户端已直接 `_battery.value = result.info`（携带 overall 字段），**无需改动**；仅确认回归测试
保证 `withLastKnownCaseBattery` 与 `inChargingCase` 在 overall-only 状态下安全：
- `withLastKnownCaseBattery` 只在 caseBattery 上操作，overall 不受影响（行为不变）。
- `inChargingCase` 依赖 left/right，overall-only 时 `known` 为空 → 返回 false（不暂停轮询），正确。

### 6. 测试

- **解析**：新建 `RoseCambrianResponseParserTest`——单字节 `0x0C` → `TwsBatteryState.overall`；
  越界 `0xFF` → 不产生 Battery（扩展现有 `MalformedProtocolResponseTest`，`unknown cambrian battery`）。
- **IPC/投影**：`overall` → DeviceControlStore 还原 `TwsBatteryState.overall`；
  MiLink `buildMiLinkBatteryList` 投影 `[-1, X, X]`（如该函数可单元测试，补充用例）。
- **回归**：既有 Cambrian / MK2 / Lite / I5 解析测试与 Malformed 测试全部保持通过。

## 成功标准

1. ROSE CAMBRIAN 电量不再伪装成 left，解析产生 `overall`。
2. MiLink 以 `[-1, X, X]` 整机单块显示；Focus Island / 应用内 BatteryCard 以整机单值显示。
3. 组件形态（I5 / MK2 / Lite）电量行为完全不变。
4. 全部单元测试通过（`testDebugUnitTest`）。

## 不做的事（YAGNI）

- 不新增独立 `Aggregate` 类型——用 `TwsBatteryState.overall` 字段表达，避免改动整个组件树。
- 不处理充电盒/充电状态语义（Cambrian 整机无盒）。
- 不改 preset / QuickControl 的 battery 构造逻辑。
- 不改直连客户端的存储逻辑（已自动携带 overall）。