package com.crispy.tv.ai

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.backend.parseAiInsightsSlides
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
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        val slides = parseAiInsightsSlides(json.optJSONArray("slides"))
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
                        slide.backdrop.small?.let { put("small", it) }
                        slide.backdrop.medium?.let { put("medium", it) }
                        slide.backdrop.large?.let { put("large", it) }
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
