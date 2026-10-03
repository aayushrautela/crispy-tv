package com.crispy.tv.ai

import com.crispy.tv.backend.parseAiInsightsSlides
import com.crispy.tv.platform.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The [AiInsightsCache] implementation, persisted behind a [KeyValueStore], and
 * therefore reachable from `commonMain`.
 *
 * **This class is why [AiInsightsRepository] is an interface, and that reason is now
 * discharged rather than merely described.** The repository is platform-free -- its
 * three other collaborators all live in `:backend`'s `commonMain` -- and what would
 * have pinned it to `androidMain` is this class being named in its constructor while
 * holding a `Context` + `SharedPreferences` for its whole life. *A file whose
 * parameter names a concrete `Context` holder cannot be read from `commonMain` however
 * portable its own body is*, which is why the fix was a port and not a moved class:
 * what [AiInsightsCache] now says is "somewhere that persists strings", and this
 * class is free to be any of them.
 *
 * It is left with **no `java.*` use at all** -- [keyFor] used to call
 * `locale.toLanguageTag()` on the `Locale` it was handed, so the cache key was
 * already a tag. Taking the tag directly does not change a single stored key: the
 * string that reaches the store is the same string, so a device that upgrades reads
 * the entries it wrote before.
 *
 * ## `purgeLegacyEntries` lost its guard, and its batch
 *
 * The original opened one editor, removed every legacy key through it, and called
 * `apply()` once, behind an `if (legacyKeys.isEmpty()) return` that existed only to
 * skip a pointless editor round trip. [KeyValueStore] has no editor, so it is now one
 * `remove` call per key over `keys()`. **That is N writes where there was one**, and
 * for `SharedPreferences` they coalesce into the same single disk flush, so the
 * observable behaviour is unchanged; a file-backed store does not coalesce them, which
 * is the one place this landing costs anything -- and it runs once per store instance,
 * over a prefix last written by a version from before the key format changed.
 *
 * **The guard is gone because "no keys" is now genuinely free**, not because it was
 * forgotten: an empty `forEach` makes no calls, where the old code needed the guard to
 * avoid building an editor and applying an empty change to it.
 */

class AiInsightsCacheStore(private val store: KeyValueStore) : AiInsightsCache {

    init {
        purgeLegacyEntries()
    }

    override fun load(
        itemId: String,
        languageTag: String,
    ): AiInsightsResult? {
        val normalizedItemId = itemId.trim()
        if (normalizedItemId.isBlank()) return null
        val raw = store.getString(keyFor(normalizedItemId, languageTag)) ?: return null
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

    override fun save(
        itemId: String,
        languageTag: String,
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

        store.putString(keyFor(normalizedItemId, languageTag), json.toString())
    }

    private fun purgeLegacyEntries() {
        store.keys().filter { it.startsWith(LEGACY_CACHE_PREFIX) }.forEach(store::remove)
    }

    private fun keyFor(itemId: String, languageTag: String): String =
        "$CACHE_PREFIX${itemId}_${languageTag.ifBlank { DEFAULT_LOCALE_TAG }}"

    companion object {
        private const val CACHE_PREFIX = "ai_ins_v3_"
        private const val LEGACY_CACHE_PREFIX = "ai_ins_v2_"
        private const val DEFAULT_LOCALE_TAG = "en-US"
    }
}
