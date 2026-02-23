package com.crispy.tv.ai

data class AiInsightCard(
    val type: String,
    val title: String,
    val category: String,
    val content: String
)

data class AiInsightsResult(
    val insights: List<AiInsightCard>,
    val trivia: String
)
