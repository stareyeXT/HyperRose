package com.dohex.hyperrose.util

import android.app.Notification
import android.graphics.drawable.Icon
import android.os.Bundle
import com.dohex.hyperrose.model.asBatteryLevelOrNull
import com.xzakota.hyper.notification.focus.FocusNotification

/**
 * 构建 HyperOS 焦点通知 / 超级岛 V3 payload。
 *
 * 参考 SonyPods：同一份 payload 同时承担
 * - 通知栏常驻卡片（iconTextInfo + textButton 降噪按钮）
 * - 超级岛摘要（bigIslandArea）
 * - AOD 息屏电量（aodTitle + aodPic）
 */
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
        headsetIcon: Icon? = null,
        ancAction: Notification.Action? = null,
        ancLabel: String? = null,
        firstFloat: Boolean = false,
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

        val extras =
            FocusNotification.buildV3 {
                val picLeft = if (leftIcon != null) createPicture("key_pic_left", leftIcon) else null
                val picRight = if (rightIcon != null) createPicture("key_pic_right", rightIcon) else null
                val picHeadset =
                    if (headsetIcon != null) createPicture("key_headset", headsetIcon) else picLeft

                // 通知栏卡片可上浮为焦点岛；后续电量更新原地刷新，不再重复上浮。
                enableFloat = true
                updatable = true
                ticker = TICKER_TEXT
                if (picHeadset != null) tickerPic = picHeadset
                if (firstFloat) islandFirstFloat = true

                // 常驻通知行（通知栏显示 + AOD 依赖）。
                isShowNotification = true
                picHeadset?.let { aodPic = it }
                aodTitle = aodTitleValue

                if (picHeadset != null) {
                    iconTextInfo {
                        animIconInfo {
                            type = 0
                            src = picHeadset
                        }
                        title = deviceName
                        content = baseContent
                    }
                }

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
                    shareData {
                        title = TICKER_TEXT
                        content = baseContent
                        shareContent = baseContent
                    }
                }

                // 通知栏降噪循环按钮：作为焦点通知的 textButton，命令直接送蓝牙进程。
                if (ancAction != null && !ancLabel.isNullOrEmpty()) {
                    textButton {
                        addActionInfo {
                            type = 1
                            action = createAction("key_anc_cycle", ancAction)
                            actionTitle = ancLabel
                        }
                    }
                }
            }

        // AOD 图 key 与 DSL 内 picHeadset 的创建顺序一致；无图时不注入 aodPic。
        val aodPicKey =
            when {
                headsetIcon != null -> "key_headset"
                leftIcon != null -> "key_pic_left"
                else -> null
            }
        injectAodIntoJson(extras, aodTitleValue, aodPicKey)
        return extras
    }

    /**
     * focus-api 1.4 的 DSL 未保证 aodTitle/aodPic 落在 SystemUI 实际读取的
     * param_v2 层级。像 SonyPods 一样直接改写 miui.focus.param JSON，确保
     * 息屏 AOD 电量稳定显示。
     */
    private fun injectAodIntoJson(
        extras: Bundle,
        aodTitle: String?,
        aodPicKey: String?,
    ) {
        if (aodTitle.isNullOrEmpty() && aodPicKey.isNullOrEmpty()) return
        runCatching {
            val raw = extras.getString("miui.focus.param") ?: return
            val root = org.json.JSONObject(raw)
            val pv2 = root.optJSONObject("param_v2") ?: org.json.JSONObject().also { root.put("param_v2", it) }
            if (!aodTitle.isNullOrEmpty()) pv2.put("aodTitle", aodTitle)
            if (!aodPicKey.isNullOrEmpty()) pv2.put("aodPic", aodPicKey)
            root.put("param_v2", pv2)
            extras.putString("miui.focus.param", root.toString())
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
