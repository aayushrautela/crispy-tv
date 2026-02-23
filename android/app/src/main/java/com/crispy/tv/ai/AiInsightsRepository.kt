package com.crispy.tv.ai

import com.crispy.tv.home.MediaDetails
import com.crispy.tv.metadata.tmdb.TmdbReview
import com.crispy.tv.settings.AiInsightsModelType
import com.crispy.tv.settings.AiInsightsSettingsStore
import org.json.JSONArray
import org.json.JSONObject

class AiInsightsRepository(
    private val openRouterClient: OpenRouterClient,
    private val settingsStore: AiInsightsSettingsStore,
    private val cacheStore: AiInsightsCacheStore,
) {
    fun loadCached(tmdbId: Int): AiInsightsResult? = cacheStore.load(tmdbId)

    suspend fun generate(tmdbId: Int, details: MediaDetails, reviews: List<TmdbReview>): AiInsightsResult {
        val snapshot = settingsStore.loadSnapshot()
        val key = snapshot.openRouterKey.trim()
        if (key.isEmpty()) {
            throw IllegalStateException("AI is not configured yet. Add an OpenRouter key in settings.")
        }

        val model = selectModel(snapshot.settings.modelType, snapshot.settings.customModelName)
        val prompt = buildPrompt(details = details, reviews = reviews)
        val content = openRouterClient.chatCompletionsJsonObject(apiKey = key, model = model, userPrompt = prompt)
        val result = parseAiInsightsResult(content)
        cacheStore.save(tmdbId, result)
        return result
    }

    private fun selectModel(type: AiInsightsModelType, custom: String): String {
        return when (type) {
            AiInsightsModelType.DEEPSEEK_R1 -> "deepseek/deepseek-r1-0528:free"
            AiInsightsModelType.NVIDIA_NEMOTRON -> "nvidia/nemotron-3-nano-30b-a3b:free"
            AiInsightsModelType.CUSTOM -> custom.trim().ifEmpty { "deepseek/deepseek-r1-0528:free" }
        }
    }

    private fun buildPrompt(details: MediaDetails, reviews: List<TmdbReview>): String {
        val title = details.title
        val year = details.year
        val plot = details.description?.trim().orEmpty().ifBlank { "N/A" }
        val rating = details.rating?.trim().orEmpty().ifBlank { "N/A" }
        val genres = details.genres.joinToString(", ").ifBlank { "N/A" }

        val formattedReviews =
            if (reviews.isEmpty()) {
                "No user reviews available."
            } else {
                reviews
                    .take(10)
                    .joinToString("\n---\n") { r ->
                        val author = r.author.ifBlank { "Unknown" }
                        val authorRating = r.rating?.toString() ?: "N/A"
                        val content = r.content
                            .replace("\n", " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .let { if (it.length > 500) it.take(500) + "..." else it }
                        "(Author: $author, Rating: $authorRating) \"$content\""
                    }
            }

        return """
Be a film enthusiast, not a critic. Use simple, conversational, and exciting English.
Avoid complex words, academic jargon, or flowery prose. Write like you're talking to a friend.

Do NOT use generic headings.

Context:
Title: $title ($year)
Plot: $plot
Rating: $rating
Genres: $genres

User Reviews:
$formattedReviews

Task:
Generate a JSON object with:

- insights: an array of 3 objects. Each object must include:
  - category: a short uppercase label (e.g. CONSENSUS, VIBE, STYLE)
  - title: a punchy, short headline
  - content: 2-3 sentences
  - type: one of ["consensus","performance","theme","vibe","style","controversy","character"]

- trivia: one "Did you know?" fact (1-2 sentences)

Return ONLY valid JSON.
""".trimIndent()
    }

    private fun parseAiInsightsResult(content: String): AiInsightsResult {
        val extracted = extractJsonObject(content)
        val json = JSONObject(extracted)

        val trivia = json.optString("trivia", "").trim()
        val insightsArray = json.optJSONArray("insights") ?: JSONArray()
        val insights =
            buildList {
                for (i in 0 until insightsArray.length()) {
                    val obj = insightsArray.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "").trim()
                    val category = obj.optString("category", "").trim()
                    val content = obj.optString("content", "").trim()
                    val type = obj.optString("type", "").trim()
                    if (title.isBlank() || category.isBlank() || content.isBlank() || type.isBlank()) continue
                    add(AiInsightCard(type = type, title = title, category = category, content = content))
                }
            }

        if (insights.isEmpty()) {
            throw IllegalStateException("AI insights are unavailable right now. Please try again in a moment.")
        }

        return AiInsightsResult(insights = insights, trivia = trivia)
    }

    private fun extractJsonObject(text: String): String {
        val trimmed = text.trim()

        val unfenced =
            trimmed
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return unfenced.substring(start, end + 1)
        }

        return unfenced
    }
}
