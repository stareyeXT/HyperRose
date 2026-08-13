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
