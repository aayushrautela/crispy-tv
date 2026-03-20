package com.crispy.tv.ai

import androidx.compose.runtime.Immutable

@Immutable
data class AiInsightCard(
    val type: String,
    val title: String,
    val category: String,
    val content: String
)

@Immutable
data class AiInsightsResult(
    val insights: List<AiInsightCard>,
    val trivia: String
)
