package com.crispy.tv.ai

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.player.MetadataLabMediaType
import org.json.JSONArray
import org.json.JSONObject

class AiInsightsCacheStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(tmdbId: Int, mediaType: MetadataLabMediaType): AiInsightsResult? {
        val raw = prefs.getString(keyFor(mediaType, tmdbId), null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

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

        if (insights.isEmpty()) return null
        return AiInsightsResult(insights = insights, trivia = trivia)
    }

    fun save(tmdbId: Int, mediaType: MetadataLabMediaType, result: AiInsightsResult) {
        val json = JSONObject()
        val insightsArray = JSONArray()
        result.insights.forEach { card ->
            insightsArray.put(
                JSONObject()
                    .put("type", card.type)
                    .put("title", card.title)
                    .put("category", card.category)
                    .put("content", card.content)
            )
        }
        json.put("insights", insightsArray)
        json.put("trivia", result.trivia)

        prefs.edit().putString(keyFor(mediaType, tmdbId), json.toString()).apply()
    }

    private fun keyFor(mediaType: MetadataLabMediaType, tmdbId: Int): String =
        "$CACHE_PREFIX${mediaType.name}_$tmdbId"

    companion object {
        private const val PREFS_NAME = "ai_insights_cache"
        private const val CACHE_PREFIX = "ai_ins_"
    }
}
