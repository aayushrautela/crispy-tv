package com.crispy.tv.ai

import com.crispy.tv.backend.CrispyBackendClient

enum class AiInsightSlideKey(val wire: String) {
    THE_GOOD_STUFF("the_good_stuff"),
    THE_CATCH("the_catch"),
    STANDOUT_ELEMENT("standout_element"),
    TRIVIA("trivia"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String?): AiInsightSlideKey =
            value?.trim()?.let { raw -> entries.firstOrNull { it.wire == raw } } ?: UNKNOWN
    }
}

enum class AiInsightSlideKind(val wire: String) {
    PROSE("prose"),
    STANDOUT("standout"),
    TRIVIA("trivia");

    companion object {
        fun fromWire(value: String?): AiInsightSlideKind =
            value?.trim()?.let { raw -> entries.firstOrNull { it.wire == raw } } ?: PROSE
    }
}

enum class AiInsightStandoutTag(val wire: String) {
    PERFORMANCE("PERFORMANCE"),
    VISUALS("VISUALS"),
    STORY("STORY"),
    DIRECTION("DIRECTION"),
    WORLD_BUILDING("WORLD_BUILDING");

    companion object {
        fun fromWire(value: String?): AiInsightStandoutTag? =
            value?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { raw -> entries.firstOrNull { it.wire == raw } }
    }
}

data class AiInsightSlide(
    val key: AiInsightSlideKey,
    val label: String,
    val kind: AiInsightSlideKind,
    val body: String?,
    val tag: AiInsightStandoutTag?,
    val focus: String?,
    val context: String?,
    val backdrop: CrispyBackendClient.ResponsiveImageSet,
    val accent: String,
)

data class AiInsightsResult(
    val slides: List<AiInsightSlide>,
)
