package com.crispy.tv.settings

import java.util.Locale

enum class AiInsightsMode(val raw: String) {
    OFF("off"),
    ON_DEMAND("on-demand"),
    ALWAYS("always");

    companion object {
        fun fromRaw(raw: String?): AiInsightsMode {
            return when (raw?.trim()?.lowercase(Locale.US)) {
                OFF.raw -> OFF
                ON_DEMAND.raw -> ON_DEMAND
                ALWAYS.raw -> ALWAYS
                else -> ON_DEMAND
            }
        }
    }
}

enum class AiInsightsModelType(val raw: String) {
    DEEPSEEK_R1("deepseek-r1"),
    NVIDIA_NEMOTRON("nvidia-nemotron"),
    CUSTOM("custom");

    companion object {
        fun fromRaw(raw: String?): AiInsightsModelType {
            return when (raw?.trim()?.lowercase(Locale.US)) {
                DEEPSEEK_R1.raw -> DEEPSEEK_R1
                NVIDIA_NEMOTRON.raw -> NVIDIA_NEMOTRON
                CUSTOM.raw -> CUSTOM
                else -> DEEPSEEK_R1
            }
        }
    }
}

data class AiInsightsSettings(
    val mode: AiInsightsMode = AiInsightsMode.ON_DEMAND,
    val modelType: AiInsightsModelType = AiInsightsModelType.DEEPSEEK_R1,
    val customModelName: String = ""
)

data class AiInsightsSettingsSnapshot(
    val settings: AiInsightsSettings,
    val openRouterKey: String
)
