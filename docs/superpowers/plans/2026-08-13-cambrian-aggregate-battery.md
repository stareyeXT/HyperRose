# ROSE CAMBRIAN 整机电量（Aggregate）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 ROSE CAMBRIAN 的电量以整机单值（`TwsBatteryState.overall`）贯穿解析、IPC、MiLink、Focus Island 与应用 UI，不再伪装成 left 耳。

**Architecture:** 仿 HyperEars `BatteryState.Aggregate`，在模型 `TwsBatteryState` 上新增 `overall` 字段；Cambrian 解析器两条单字节路径返回 `overall`；新增 IPC extra `EXTRA_OVERALL_LEVEL` 让跨进程可还原；各显示面（MiLink 列表 / Focus Island / BatteryCard）识别 `overall` 后单值投影，组件形态（I5/MK2/Lite）行为不变。

**Tech Stack:** Kotlin, Android Gradle Plugin, JUnit4。

## Global Constraints

- 组件形态（EarfreeI5 / BudsFeelMk2 / BudsFeelLite）电量行为**必须不变**。
- `overall` 语义与 left/right/case 互斥：整机形态不产生组件电量。
- 电量合法范围 `0..100`，用现有 `asBatteryLevelOrNull()` 校验。
- 新增字段带默认参数，保持向后兼容；不改 preset / QuickControl 构造逻辑、不改直连客户端的存储逻辑。
- 测试命令：`.\gradlew.bat :app:testDebugUnitTest --no-daemon`（Windows PowerShell）。

---

### Task 1: 模型层新增 `overall` 字段与单值辅助函数

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/model/BatteryState.kt`
- Create: `app/src/test/kotlin/com/dohex/hyperrose/model/BatteryStateTest.kt`

**Interfaces:**
- Produces:
  - `TwsBatteryState(left, right, caseBattery, overall: Int? = null)`
  - `fun TwsBatteryState.isSingleValue(): Boolean` —— overall 非空，或 right/case 均为 null。
  - `fun TwsBatteryState.singleDisplayValue(): Int?` —— overall 优先，否则仅 left 存在时返回 left 电量。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.dohex.hyperrose.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStateTest {

    @Test
    fun `overall state is single value and displays overall`() {
        val state = TwsBatteryState(overall = 60)
        assertTrue(state.isSingleValue())
        assertEquals(60, state.singleDisplayValue())
    }

    @Test
    fun `component state with left only is single value`() {
        val state = TwsBatteryState(left = EarBatteryState(60, false))
        assertTrue(state.isSingleValue())
        assertEquals(60, state.singleDisplayValue())
    }

    @Test
    fun `component state with left and right is not single value`() {
        val state = TwsBatteryState(
            left = EarBatteryState(60, false),
            right = EarBatteryState(58, false),
        )
        assertFalse(state.isSingleValue())
        assertNull(state.singleDisplayValue())
    }

    @Test
    fun `withLastKnownCaseBattery keeps overall`() {
        val state = TwsBatteryState(overall = 60)
        val merged = state.withLastKnownCaseBattery(TwsBatteryState(caseBattery = 90))
        assertEquals(60, merged.overall)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.dohex.hyperrose.model.BatteryStateTest"`
Expected: FAIL（`isSingleValue` 未定义 / `overall` 不存在，编译错误）。

- [ ] **Step 3: 实现模型字段与辅助函数**

编辑 `BatteryState.kt`，为 `TwsBatteryState` 增加 `overall` 字段并在文件末尾追加两个函数：

```kotlin
data class TwsBatteryState(
    val left: EarBatteryState? = null,
    val right: EarBatteryState? = null,
    val caseBattery: Int? = null,
    val overall: Int? = null, // 整机单值（头戴/整机形态），0..100
)
```

```kotlin
/** 是否单值形态：整机单值，或组件形态仅 left 有值（无 right、无盒）。 */
fun TwsBatteryState.isSingleValue(): Boolean =
    overall != null || (right == null && caseBattery == null)

/** 单值展示电量：整机单值优先；组件单耳形态返回 left 电量。 */
fun TwsBatteryState.singleDisplayValue(): Int? =
    overall ?: left?.level?.takeIf { right == null && caseBattery == null }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.dohex.hyperrose.model.BatteryStateTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/com/dohex/hyperrose/model/BatteryState.kt app/src/test/kotlin/com/dohex/hyperrose/model/BatteryStateTest.kt
git commit -m "feat(model): add overall whole-device battery and single-value helpers"
```

---

### Task 2: IPC 新增 `EXTRA_OVERALL_LEVEL`

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/ipc/HyperRoseIpc.kt:75-76`

**Interfaces:**
- Consumes: 无。
- Produces: `HyperRoseIpc.EXTRA_OVERALL_LEVEL: String`

- [ ] **Step 1: 在 `EXTRA_CASE_LEVEL` 之后新增常量**

```kotlin
const val EXTRA_CASE_LEVEL = "$EXTRA_PREFIX.case_level"
const val EXTRA_OVERALL_LEVEL = "$EXTRA_PREFIX.overall_level"
const val EXTRA_LEFT_IMAGE = "$EXTRA_PREFIX.left_image"
```

- [ ] **Step 2: 验证编译产物**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.dohex.hyperrose.model.BatteryStateTest"`
Expected: 既有测试仍 PASS（编译通过即验证常量可用，无单测专门覆盖常量）。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/com/dohex/hyperrose/ipc/HyperRoseIpc.kt
git commit -m "feat(ipc): add EXTRA_OVERALL_LEVEL broadcast extra"
```

---

### Task 3: Cambrian 解析器单字节整机值改为 `overall`

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/profile/rose_cambrian/RoseCambrianResponseParser.kt:68-76`（TLV 单值分支）和 `:124-147`（`parseType4Response`）
- Create: `app/src/test/kotlin/com/dohex/hyperrose/profile/RoseCambrianResponseParserTest.kt`

**Interfaces:**
- Consumes: `TwsBatteryState(overall = ...)`（Task 1）、`DeviceResponse.Battery(info: TwsBatteryState)`、`asBatteryLevelOrNull()`。
- Produces: `RoseCambrianResponseParser.parse(data)` 在整机单值路径产生 `DeviceResponse.Battery(TwsBatteryState(overall = level))`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.dohex.hyperrose.profile

import com.dohex.hyperrose.profile.rose_cambrian.RoseCambrianProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoseCambrianResponseParserTest {

    private val protocol = RoseCambrianProfile.protocol

    @Test
    fun `type4 single byte battery produces overall`() {
        // DD 00 04 0C 3C AA -> 0x3C = 60
        val frame = byteArrayOf(
            0xDD.toByte(), 0x00, 0x04, 0x0C, 0x3C, 0xAA.toByte(),
        )
        val battery = protocol.parseResponse(frame)
            .filterIsInstance<DeviceResponse.Battery>()
        assertEquals(1, battery.size)
        assertEquals(60, battery[0].info.overall)
    }

    @Test
    fun `type4 out of range byte produces Unknown`() {
        // DD 00 04 0C FF AA -> 0xFF 越界
        val frame = byteArrayOf(
            0xDD.toByte(), 0x00, 0x04, 0x0C, 0xFF.toByte(), 0xAA.toByte(),
        )
        val results = protocol.parseResponse(frame)
        assertTrue("Expected Unknown, got $results", results.all { it is DeviceResponse.Unknown })
    }

    @Test
    fun `status tlv single nonzero among three produces overall`() {
        // DD 00 15 [00 04 0C FF 3C FF] AA —— len=04 type=0C values=FF 3C FF
        val frame = byteArrayOf(
            0xDD.toByte(), 0x00, 0x15, 0x04, 0x0C,
            0xFF.toByte(), 0x3C, 0xFF.toByte(), 0xAA.toByte(),
        )
        val battery = protocol.parseResponse(frame)
            .filterIsInstance<DeviceResponse.Battery>()
        assertEquals(1, battery.size)
        assertEquals(60, battery[0].info.overall)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.dohex.hyperrose.profile.RoseCambrianResponseParserTest"`
Expected: FAIL（目前 `overall == null`）。

- [ ] **Step 3: 改 `parseType4Response` 单值返回整体**

将 `RoseCambrianResponseParser.kt` 现有的 `private fun parseType4Response(data: ByteArray): DeviceResponse` 中 `0x0C` 分支整体替换为：

```kotlin
private fun parseType4Response(data: ByteArray): DeviceResponse {
    if (data.size < 6) return DeviceResponse.Unknown
    val subType = data[3].toInt() and 0xFF
    return when (subType) {
        0x0C -> {
            val level = data[4].toInt() and 0xFF
            DeviceResponse.Battery(
                TwsBatteryState(overall = level.asBatteryLevelOrNull() ?: return DeviceResponse.Unknown),
            )
        }
        else -> DeviceResponse.Unknown
    }
}
```

说明：type4 的载荷是单字节整机值，直接取 `data[4]` 并用 `asBatteryLevelOrNull()` 严格校验 `0..100`，非法值（`0xFF`）返回 `DeviceResponse.Unknown`（此简化替代旧的多值/折叠逻辑）。

- [ ] **Step 4: 改 TLV 单值分支为整体**

将 `parseTlvBlock` 的 `0x0C` 分支中 `if (nonZero.size == 1 && values.size >= 3)` 里的 `TwsBatteryState` 构造替换为：

```kotlin
if (nonZero.size == 1 && values.size >= 3) {
    results.add(
        DeviceResponse.Battery(
            TwsBatteryState(overall = nonZero[0]),
        ),
    )
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.dohex.hyperrose.profile.RoseCambrianResponseParserTest"`
Expected: PASS。

- [ ] **Step 6: 跑全量单测确认无回归**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
Expected: 全部 PASS（含 MalformedProtocolResponseTest 的 `unknown cambrian battery`）。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/kotlin/com/dohex/hyperrose/profile/rose_cambrian/RoseCambrianResponseParser.kt app/src/test/kotlin/com/dohex/hyperrose/profile/RoseCambrianResponseParserTest.kt
git commit -m "refactor(cambrian): expose single-byte battery as overall, not left"
```

---

### Task 4: `DeviceSession` 广播携带并还原 `overall`

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/DeviceSession.kt:71-110`
- Test: `app/src/test/kotlin/com/dohex/hyperrose/model/BatteryStateTest.kt`（复用 `isSingleValue` / `singleDisplayValue`，已覆盖）

**Interfaces:**
- Consumes: `HyperRoseIpc.EXTRA_OVERALL_LEVEL`（Task 2）、`TwsBatteryState.isSingleValue()` / `singleDisplayValue()`（Task 1）。
- Produces: BATTERY_CHANGED / SHOW_ISLAND / DEVICE_CONNECTED 广播的 `EXTRA_OVERALL_LEVEL` 语义约定：
  - `overall` 非空时：`EXTRA_LEFT_LEVEL = -1`、`EXTRA_RIGHT_LEVEL = -1`、`EXTRA_CASE_LEVEL = overall`、`EXTRA_OVERALL_LEVEL = overall`。
  - 组件形态：行为与现状完全一致。

- [ ] **Step 1: 改 `handleResponse` 的 BATTERY_CHANGED 广播**

在 `DeviceSession.kt` BATTERY_CHANGED 的 extras lambda（第 71-84 行）末尾追加（在既有字段之后、`EXTRA_DEVICE` 之前均可）：

```kotlin
putExtra(HyperRoseAction.EXTRA_OVERALL_LEVEL, battery.overall ?: -1)
```

并将 EXTRA_LEFT/RIGHT/CASE 三项改为以 overall 优先，组件形态保持原值：

```kotlin
putExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, battery.left?.level ?: -1)
putExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, battery.right?.level ?: -1)
putExtra(HyperRoseAction.EXTRA_CASE_LEVEL, battery.caseBattery ?: -1)
```

（保持不变即可——overall 只通过 `EXTRA_OVERALL_LEVEL` 传递，组件字段不伪造。）

- [ ] **Step 2: 改 SHOW_ISLAND 广播的 isMono 判定**

第 89-100 行将：

```kotlin
val isMono = battery.right == null && battery.caseBattery == null
...
putExtra(HyperRoseAction.EXTRA_CASE_LEVEL, if (isMono) (battery.left?.level ?: -1) else (battery.caseBattery ?: -1))
```

改为：

```kotlin
val isMono = battery.isSingleValue()
...
putExtra(HyperRoseAction.EXTRA_CASE_LEVEL, if (isMono) (battery.singleDisplayValue() ?: -1) else (battery.caseBattery ?: -1))
```

- [ ] **Step 3: 改 DEVICE_CONNECTED 广播**

第 229-233 行 `currentBattery?.let { ... }` 内追加：

```kotlin
b.overall?.let { putExtra(HyperRoseAction.EXTRA_OVERALL_LEVEL, it) }
```

- [ ] **Step 4: 运行单测确认无回归**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/com/dohex/hyperrose/hook/DeviceSession.kt
git commit -m "feat(session): carry and restore overall battery across broadcasts"
```

---

### Task 5: `BluetoothProcessHook` 颜色变更重发 SHOW_ISLAND 识别 `overall`

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/BluetoothProcessHook.kt:317-327`
- Test: `app/src/test/kotlin/com/dohex/hyperrose/model/BatteryStateTest.kt`（复用辅助函数）

**Interfaces:**
- Consumes: `TwsBatteryState.isSingleValue()` / `singleDisplayValue()`（Task 1）。
- Produces: DEVICE_COLOR_CHANGED 重发的 SHOW_ISLAND 对 `overall` 单值一致。

- [ ] **Step 1: 改 isMono 判定与 case 回填**

第 317、325-327 行将：

```kotlin
val isMono = battery.right == null && battery.caseBattery == null
...
putExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, if (isMono) -1 else (battery.left?.level ?: -1))
putExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, if (isMono) -1 else (battery.right?.level ?: -1))
putExtra(HyperRoseAction.EXTRA_CASE_LEVEL, if (isMono) (battery.left?.level ?: -1) else (battery.caseBattery ?: -1))
```

改为：

```kotlin
val isMono = battery.isSingleValue()
...
putExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, if (isMono) -1 else (battery.left?.level ?: -1))
putExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, if (isMono) -1 else (battery.right?.level ?: -1))
putExtra(HyperRoseAction.EXTRA_CASE_LEVEL, if (isMono) (battery.singleDisplayValue() ?: -1) else (battery.caseBattery ?: -1))
```

- [ ] **Step 2: 运行单测确认无回归**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
Expected: 全部 PASS。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/com/dohex/hyperrose/hook/BluetoothProcessHook.kt
git commit -m "feat(bluetooth-hook): recognize overall battery when resending island"
```

---

### Task 6: `MiLinkProcessHook` overall 投影为 `[-1, X, X]`

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/MiLinkProcessHook.kt:321-332`（BATTERY_CHANGED 处理）
- Test：无新增——`buildMiLinkBatteryList` 依赖 hook 实例字段，不做单测（spec 4d）。

**Interfaces:**
- Consumes: `HyperRoseIpc.EXTRA_OVERALL_LEVEL`（Task 2）。
- Produces: BATTERY_CHANGED 时设置 `currentLeftBattery = currentRightBattery = overall`、`currentCaseBattery = -1`，
  使 `buildMiLinkBatteryList()` 输出 `[-1, X, X, 0, 0, 0]`。

- [ ] **Step 1: 改 BATTERY_CHANGED 分支**

将第 321-332 行替换为：

```kotlin
HyperRoseAction.BATTERY_CHANGED -> {
    val overallLevel =
        intent.getIntExtra(HyperRoseAction.EXTRA_OVERALL_LEVEL, -1)
    if (overallLevel in 0..100) {
        currentLeftBattery = overallLevel
        currentRightBattery = overallLevel
        currentCaseBattery = -1
        currentLeftCharging = false
        currentRightCharging = false
    } else {
        currentLeftBattery =
            intent.getIntExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, -1)
        currentRightBattery =
            intent.getIntExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, -1)
        currentCaseBattery =
            intent.getIntExtra(HyperRoseAction.EXTRA_CASE_LEVEL, -1)
        currentLeftCharging =
            intent.getBooleanExtra(HyperRoseAction.EXTRA_LEFT_CHARGING, false)
        currentRightCharging =
            intent.getBooleanExtra(HyperRoseAction.EXTRA_RIGHT_CHARGING, false)
    }
}
```

- [ ] **Step 2: 运行单测确认无回归**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
Expected: 全部 PASS。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/com/dohex/hyperrose/hook/MiLinkProcessHook.kt
git commit -m "feat(milink): project overall battery as [-1, X, X]"
```

---

### Task 7: `HeadsetServiceBinderHook` 从广播还原 `overall`

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/hook/HeadsetServiceBinderHook.kt:839-857`

**Interfaces:**
- Consumes: `HyperRoseIpc.EXTRA_OVERALL_LEVEL`（Task 2）、`asBatteryLevelOrNull()`。
- Produces: `Intent.parseBatteryFromExtras()` 在 `overall` 存在时返回 `TwsBatteryState(overall = level)`。

- [ ] **Step 1: 改 `parseBatteryFromExtras`**

在读取 caseLevel 之后、`if (leftLevel == null && rightLevel == null && caseLevel == null) return null` 之前插入：

```kotlin
val overallLevel =
    getIntExtra(HyperRoseAction.EXTRA_OVERALL_LEVEL, -1).asBatteryLevelOrNull()
if (overallLevel != null) return TwsBatteryState(overall = overallLevel)
```

- [ ] **Step 2: 运行单测确认无回归**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
Expected: 全部 PASS。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/com/dohex/hyperrose/hook/HeadsetServiceBinderHook.kt
git commit -m "feat(headset-binder): restore overall battery from broadcast"
```

---

### Task 8: App 端 `DeviceControlStore` 还原 overall + `BatteryCard` 单值展示

**Files:**
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/ui/state/DeviceControlStore.kt:939-981`
- Modify: `app/src/main/kotlin/com/dohex/hyperrose/ui/component/BatteryCard.kt:33-65`
- Test: `app/src/test/kotlin/com/dohex/hyperrose/model/BatteryStateTest.kt`（复用 `isSingleValue`）

**Interfaces:**
- Consumes: `HyperRoseIpc.EXTRA_OVERALL_LEVEL`（Task 2）、`TwsBatteryState.overall`（Task 1）。
- Produces: `DeviceControlStore.parseBattery(intent)` 返回 overall 还原；`BatteryCard` 渲染"整机 XX%"单格子。

- [ ] **Step 1: 改 `DeviceControlStore.parseBattery`**

在 `parseBattery(intent)` 函数体最前面（读取 leftLevel 之前）插入：

```kotlin
val overallLevel =
    intent.getIntExtra(HyperRoseAction.EXTRA_OVERALL_LEVEL, -1).asBatteryLevelOrNull()
if (overallLevel != null) return TwsBatteryState(overall = overallLevel)
```

- [ ] **Step 2: 改 `BatteryCard` 渲染**

`BatteryCard` 已有 `val b = battery`（第 34 行），**不要重复声明**。仅将第 35-64 行的 `Row { ... }` 内容按如下替换（保留外层 `val b = battery` 与 Row 头部）：

```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceEvenly,
) {
    if (b?.overall != null) {
        BatteryCell(
            iconRes = R.drawable.battery_charge,
            label = "整机",
            value = b.overall,
            charging = false,
            modifier = Modifier.weight(1f),
        )
    } else {
        BatteryCell(
            iconRes = R.drawable.battery_left,
            label = "左耳",
            value = b?.left?.level,
            charging = b?.left?.isCharging == true,
            modifier = Modifier.weight(1f),
        )
        if (b?.right != null) {
            BatteryCell(
                iconRes = R.drawable.battery_right,
                label = "右耳",
                value = b.right.level,
                charging = b.right.isCharging,
                modifier = Modifier.weight(1f),
            )
        }
        if (b?.caseBattery != null) {
            BatteryCell(
                iconRes = R.drawable.battery_charge,
                label = "充电盒",
                value = b.caseBattery,
                charging = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
```

（原 `Row` 内 `battery_left` 图标 + "左耳"格是无条件渲染的首格，替换后仍保留在 else 分支首格。）

- [ ] **Step 3: 运行单测确认无回归**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
Expected: 全部 PASS（BatteryCard 为 Compose UI，无单测；编译通过即可）。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/kotlin/com/dohex/hyperrose/ui/state/DeviceControlStore.kt app/src/main/kotlin/com/dohex/hyperrose/ui/component/BatteryCard.kt
git commit -m "feat(ui): restore and render whole-device battery as single cell"
```

---

### Task 9: 终验与回归

**Files:** 无新增/修改。

**Interfaces:** 全链路 `overall` 已接通。

- [ ] **Step 1: 全量单测**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
Expected: 全部 PASS。

- [ ] **Step 2: Lint 检查**

Run: `.\gradlew.bat :app:lintDebug --no-daemon`
Expected: 无新增 error（可保留已有 warning）。

- [ ] **Step 3: 编译 verify（可选）**

Run: `.\gradlew.bat :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交状态确认**

```bash
git status
git log --oneline -9
```

Expected: 9 个任务各自独立提交完成，工作区干净。

- [ ] **Step 5: 真机验证清单（用户执行）**

1. 连接 ROSE CAMBRIAN，查看应用"电量"卡片显示单个"整机 XX%"。
2. HyperOS 控制中心 MiLink 耳机卡片显示整机单块电量。
3. 弹岛 / Focus Island 显示单个百分比。
4. 回归 EARFREE i5：依然是 左耳/右耳/充电盒 三格电量。