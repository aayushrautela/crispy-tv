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

data class AiInsightsSettings(
    val mode: AiInsightsMode = AiInsightsMode.ON_DEMAND,
)

data class AiInsightsSettingsSnapshot(
    val settings: AiInsightsSettings,
    val openRouterKey: String
)
