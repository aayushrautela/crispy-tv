package com.crispy.tv.ai

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.backend.parseAiInsightsSlides
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        // **Only the reader used to convert**, and that sentence is what this
        // port deletes: `save` below now builds with `buildJsonObject` too, so
        // both sides of the boundary are `JsonElement` and the note that
        // described the boundary as the reader's alone is gone. `Json.parseToJsonElement`
        // raises `SerializationException` where `JSONObject(body)` raised
        // `JSONException`, which the `runCatching` here absorbs either way --
        // and the census measured **zero** `JSONException` occurrences in
        // `:app`, so nothing could have depended on the type.
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

        // `JSONObject().put(...)` -> `buildJsonObject { ... }`, because
        // `JSONObject`'s whole API *was* `put` and `JsonObject` has no mutating
        // members at all. `add` replaces `array.put(obj)`. Every value here is a
        // `String` or a `String?` on the `AiInsightSlide` data class, so `put`
        // needs no `JsonPrimitive` wrapper -- which is measured, not assumed.
        val array = buildJsonArray {
            slides.forEach { slide ->
                add(
                    buildJsonObject {
                        put("key", slide.key.wire)
                        put("label", slide.label)
                        put("kind", slide.kind.wire)
                        put("accent", slide.accent)
                        slide.body?.let { put("body", it) }
                        slide.tag?.let { put("tag", it.wire) }
                        slide.focus?.let { put("focus", it) }
                        slide.context?.let { put("context", it) }
                        if (!slide.backdrop.isEmpty) {
                            put(
                                "backdrop",
                                buildJsonObject {
                                    slide.backdrop.low?.let { put("small", it) }
                                    slide.backdrop.medium?.let { put("medium", it) }
                                    slide.backdrop.high?.let { put("large", it) }
                                },
                            )
                        }
                    },
                )
            }
        }
        val json = buildJsonObject { put("slides", array) }

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
