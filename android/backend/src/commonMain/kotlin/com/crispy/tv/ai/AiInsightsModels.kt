package com.crispy.tv.ai

import com.crispy.tv.images.ResponsiveImageSet


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

data class AiInsightSlide(
    val key: AiInsightSlideKey,
    val label: String,
    val kind: AiInsightSlideKind,
    val heading: String?,
    val body: String?,
    val backdrop: ResponsiveImageSet,
    val accent: String,
)

data class AiInsightsResult(
    val slides: List<AiInsightSlide>,
)
