package com.dohex.hyperrose.util

import android.graphics.drawable.Icon
import android.os.Bundle
import com.dohex.hyperrose.model.asBatteryLevelOrNull
import com.xzakota.hyper.notification.focus.FocusNotification

object FocusIslandBridge {
    private const val TICKER_TEXT = "HyperRose"

    fun buildBatteryIslandExtras(
        leftLevel: Int,
        rightLevel: Int,
        caseLevel: Int,
        leftCharging: Boolean,
        rightCharging: Boolean,
        islandTimeoutSeconds: Int,
        deviceName: String = "ROSE CAMBRIAN",
        leftIcon: Icon? = null,
        rightIcon: Icon? = null,
    ): Bundle? {
        val normalizedLeftLevel = leftLevel.asBatteryLevelOrNull()
        val normalizedRightLevel = rightLevel.asBatteryLevelOrNull()
        val normalizedCaseLevel = caseLevel.asBatteryLevelOrNull()

        val isMonoCase = normalizedLeftLevel == null && normalizedRightLevel == null && normalizedCaseLevel != null
        val leftText = if (isMonoCase) normalizedCaseLevel.toString() else (normalizedLeftLevel?.toString() ?: "-")
        val rightText = if (isMonoCase) "" else (normalizedRightLevel?.toString() ?: "-")
        val baseContent =
            buildBaseContent(
                leftLevel = if (isMonoCase) normalizedCaseLevel else normalizedLeftLevel,
                rightLevel = normalizedRightLevel,
                caseLevel = if (isMonoCase) null else normalizedCaseLevel,
                leftCharging = leftCharging,
                rightCharging = rightCharging,
            )
        val aodTitleValue =
            if (isMonoCase) "$normalizedCaseLevel%"
            else if (normalizedRightLevel == null) "$leftText%${if (leftCharging) "⚡" else ""}"
            else buildString {
                append("L$leftText%")
                if (leftCharging) append("⚡")
                append(" R$rightText%")
                if (rightCharging) append("⚡")
            }

        return FocusNotification.buildV3 {
            val picLeft = if (leftIcon != null) createPicture("key_pic_left", leftIcon) else null
            val picRight = if (rightIcon != null) createPicture("key_pic_right", rightIcon) else null

            enableFloat = false
            cancel = false
            ticker = TICKER_TEXT
            if (picLeft != null) tickerPic = picLeft

            isShowNotification = true
            this.aodTitle = aodTitleValue
            island {
                islandProperty = 1
                dismissIsland = false
                islandTimeout = islandTimeoutSeconds
                bigIslandArea {
                    if (picLeft != null) {
                        imageTextInfoLeft {
                            type = 1
                            picInfo {
                                type = 1
                                pic = picLeft
                            }
                            textInfo {
                                title = leftText
                                content = "%"
                            }
                        }
                    } else {
                        imageTextInfoLeft {
                            type = 1
                            textInfo {
                                title = leftText
                                content = "%"
                            }
                        }
                    }
                    if (rightText.isNotEmpty() || picRight != null) {
                        if (picRight != null) {
                            imageTextInfoRight {
                                type = 2
                                picInfo {
                                    type = 1
                                    pic = picRight
                                }
                                textInfo {
                                    title = rightText
                                    content = "%"
                                }
                            }
                        } else {
                            imageTextInfoRight {
                                type = 2
                                textInfo {
                                    title = rightText
                                    content = "%"
                                }
                            }
                        }
                    }
                }
                baseInfo {
                    type = 2
                    title = deviceName
                    content = baseContent
                }
            }
        }
    }

    private fun buildBaseContent(
        leftLevel: Int?,
        rightLevel: Int?,
        caseLevel: Int?,
        leftCharging: Boolean,
        rightCharging: Boolean,
    ): String {
        val segments = mutableListOf<String>()
        if (leftLevel != null) {
            segments += "L ${formatEarBattery(leftLevel, leftCharging)}"
        }
        if (rightLevel != null) {
            segments += "R ${formatEarBattery(rightLevel, rightCharging)}"
        }
        if (caseLevel != null) {
            segments += "C $caseLevel%"
        }
        return if (segments.isEmpty()) "电量未知" else segments.joinToString(" | ")
    }

    private fun formatEarBattery(
        level: Int,
        charging: Boolean,
    ): String = if (charging) "$level% ⚡" else "$level%"
}
