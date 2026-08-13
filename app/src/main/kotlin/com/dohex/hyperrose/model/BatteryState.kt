package com.dohex.hyperrose.model

private const val MIN_BATTERY_LEVEL = 0
private const val MAX_BATTERY_LEVEL = 100

const val UNKNOWN_BATTERY_LEVEL = 0xFF

/** 单耳电池状态 */
data class EarBatteryState(
    val level: Int,
    val isCharging: Boolean,
)

/** TWS 完整电池信息（左耳 + 右耳 + 充电盒） */
data class TwsBatteryState(
    val left: EarBatteryState? = null,
    val right: EarBatteryState? = null,
    val caseBattery: Int? = null,
    val overall: Int? = null, // 整机单值（头戴/整机形态），0..100
)

fun Int.asBatteryLevelOrNull(): Int? = takeIf { it in MIN_BATTERY_LEVEL..MAX_BATTERY_LEVEL }

fun Int.isBatteryLevelOrUnknown(): Boolean =
    asBatteryLevelOrNull() != null || this == UNKNOWN_BATTERY_LEVEL

fun TwsBatteryState.withLastKnownCaseBattery(previous: TwsBatteryState?): TwsBatteryState {
    val fallbackCaseBattery = previous?.caseBattery ?: return this
    return if (caseBattery != null) this else copy(caseBattery = fallbackCaseBattery)
}

/** 耳机是否处于充电盒内：左右耳任一存在且全部在充电（无可用数据时保守返回 false）。 */
fun TwsBatteryState.inChargingCase(): Boolean {
    val known = listOfNotNull(left, right)
    return known.isNotEmpty() && known.all { it.isCharging }
}

/** 是否单值形态：整机单值，或组件形态仅 left 有值（无 right、无盒）。 */
fun TwsBatteryState.isSingleValue(): Boolean =
    overall != null || (right == null && caseBattery == null)

/** 单值展示电量：整机单值优先；组件单耳形态返回 left 电量。 */
fun TwsBatteryState.singleDisplayValue(): Int? =
    overall ?: left?.level?.takeIf { right == null && caseBattery == null }
