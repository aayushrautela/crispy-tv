package com.crispy.tv.ai

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.backend.parseAiInsightsSlides
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class AiInsightsCacheStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        purgeLegacyEntries()
    }

    fun load(
        itemId: String,
        locale: Locale = Locale.getDefault(),
    ): AiInsightsResult? {
        val normalizedItemId = itemId.trim()
        if (normalizedItemId.isBlank()) return null
        val raw = prefs.getString(keyFor(normalizedItemId, locale), null) ?: return null
        // **Only the reader converts.** The bytes on disk are JSON whichever
        // parser reads them, so `save` below still builds with `org.json` and
        // writes a string, and this line is the whole boundary. The cast is
        // explicit rather than a helper because `optJsonArray` is `internal` to
        // `:backend`, and a cross-module `public` accessor would be the second
        // coupling this port just removed.
        val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return null

        val slides = parseAiInsightsSlides(json["slides"] as? JsonArray)
            .filter { it.key != AiInsightSlideKey.UNKNOWN }
        if (slides.isEmpty()) return null
        return AiInsightsResult(slides = slides)
    }

    fun save(
        itemId: String,
        locale: Locale = Locale.getDefault(),
        result: AiInsightsResult,
    ) {
        val normalizedItemId = itemId.trim()
        if (normalizedItemId.isBlank()) return
        val slides = result.slides.filter { it.key != AiInsightSlideKey.UNKNOWN }
        if (slides.isEmpty()) return

        val array = JSONArray()
        slides.forEach { slide ->
            val obj = JSONObject()
                .put("key", slide.key.wire)
                .put("label", slide.label)
                .put("kind", slide.kind.wire)
                .put("accent", slide.accent)
            slide.body?.let { obj.put("body", it) }
            slide.tag?.let { obj.put("tag", it.wire) }
            slide.focus?.let { obj.put("focus", it) }
            slide.context?.let { obj.put("context", it) }
            if (!slide.backdrop.isEmpty) {
                obj.put(
                    "backdrop",
                    JSONObject().apply {
                        slide.backdrop.low?.let { put("small", it) }
                        slide.backdrop.medium?.let { put("medium", it) }
                        slide.backdrop.high?.let { put("large", it) }
                    },
                )
            }
            array.put(obj)
        }
        val json = JSONObject().put("slides", array)

        prefs.edit().putString(keyFor(normalizedItemId, locale), json.toString()).apply()
    }

    private fun purgeLegacyEntries() {
        val legacyKeys = prefs.all.keys.filter { it.startsWith(LEGACY_CACHE_PREFIX) }
        if (legacyKeys.isEmpty()) return
        prefs.edit().also { editor -> legacyKeys.forEach(editor::remove) }.apply()
    }

    private fun keyFor(itemId: String, locale: Locale): String =
        "$CACHE_PREFIX${itemId}_${locale.toLanguageTag().ifBlank { DEFAULT_LOCALE_TAG }}"

    companion object {
        private const val PREFS_NAME = "ai_insights_cache"
        private const val CACHE_PREFIX = "ai_ins_v3_"
        private const val LEGACY_CACHE_PREFIX = "ai_ins_v2_"
        private const val DEFAULT_LOCALE_TAG = "en-US"
    }
}
