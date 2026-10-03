package com.crispy.tv.ai

import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.platform.RecordingKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the cache's portable policy separately from the Android storage implementation: key
 * normalization, legacy cleanup, JSON filtering, and the rule that an empty usable result is not
 * written.
 */
class AiInsightsCacheStoreTest {
    @Test
    fun saveTrimsItemIdUsesTheDefaultLanguageTagAndLoadsAllSlideFields() {
        val backing = RecordingKeyValueStore()
        val store = AiInsightsCacheStore(backing)
        val slide = slide(
            body = "  body  ",
            tag = AiInsightStandoutTag.VISUALS,
            focus = "focus",
            context = "context",
            backdrop = ResponsiveImageSet("low", "medium", "high"),
            accent = "  #123456  ",
        )

        store.save("  item-42  ", "", AiInsightsResult(listOf(slide)))

        assertTrue(backing.values.containsKey("ai_ins_v3_item-42_en-US"))
        val result = requireNotNull(store.load("item-42", ""))
        val loaded = result.slides.single()
        assertEquals(AiInsightSlideKey.THE_GOOD_STUFF, loaded.key)
        assertEquals("label", loaded.label)
        assertEquals("body", loaded.body)
        assertEquals(AiInsightStandoutTag.VISUALS, loaded.tag)
        assertEquals("focus", loaded.focus)
        assertEquals("context", loaded.context)
        assertEquals(ResponsiveImageSet("low", "medium", "high"), loaded.backdrop)
        assertEquals("#123456", loaded.accent)
    }

    @Test
    fun unknownSlidesAreDroppedBeforeSavingAndAnEmptyUsableResultWritesNothing() {
        val backing = RecordingKeyValueStore()
        val store = AiInsightsCacheStore(backing)
        val beforeCalls = backing.calls.toList()
        val unknown = slide(key = AiInsightSlideKey.UNKNOWN)

        store.save("item", "en-US", AiInsightsResult(listOf(unknown)))

        assertEquals(emptyMap(), backing.values)
        assertEquals(beforeCalls, backing.calls)
    }

    @Test
    fun loadDropsUnknownSlidesButKeepsAUsableKnownSlide() {
        val backing = RecordingKeyValueStore(
            mapOf(
                "ai_ins_v3_item_en-US" to
                    """{"slides":[{"key":"","label":"unknown","kind":"prose","body":"body","accent":""},{"key":"the_good_stuff","label":"Good","kind":"prose","body":"Body","accent":"#fff"}]}""",
            ),
        )
        val store = AiInsightsCacheStore(backing)

        val result = requireNotNull(store.load(" item ", "en-US"))

        assertEquals(1, result.slides.size)
        assertEquals(AiInsightSlideKey.THE_GOOD_STUFF, result.slides.single().key)
        assertEquals("Good", result.slides.single().label)
    }

    @Test
    fun malformedOrMissingSlidesReadAsNoCache() {
        val malformed = AiInsightsCacheStore(
            RecordingKeyValueStore(mapOf("ai_ins_v3_item_en-US" to "{not json")),
        )
        val missingSlides = AiInsightsCacheStore(
            RecordingKeyValueStore(mapOf("ai_ins_v3_item_en-US" to "{}")),
        )
        val notAnObject = AiInsightsCacheStore(
            RecordingKeyValueStore(mapOf("ai_ins_v3_item_en-US" to "{\"slides\":{}}")),
        )

        assertNull(malformed.load("item", "en-US"))
        assertNull(missingSlides.load("item", "en-US"))
        assertNull(notAnObject.load("item", "en-US"))
    }

    @Test
    fun blankItemIdsAreIgnoredWithoutReadingOrWriting() {
        val backing = RecordingKeyValueStore()
        val store = AiInsightsCacheStore(backing)
        backing.calls.clear()

        store.save("   ", "en-US", AiInsightsResult(listOf(slide())))

        assertTrue(backing.calls.isEmpty())
        assertTrue(backing.values.isEmpty())
        assertNull(store.load("   ", "en-US"))
        assertEquals(emptyList(), backing.calls)
    }

    @Test
    fun legacyEntriesArePurgedAtConstructionButCurrentAndUnrelatedKeysRemain() {
        val backing = RecordingKeyValueStore(
            mapOf(
                "ai_ins_v2_item_en-US" to "old",
                "ai_ins_v2_other" to "old",
                "ai_ins_v3_item_en-US" to "current",
                "unrelated" to "keep",
            ),
        )

        AiInsightsCacheStore(backing)

        assertFalse(backing.values.containsKey("ai_ins_v2_item_en-US"))
        assertFalse(backing.values.containsKey("ai_ins_v2_other"))
        assertEquals("current", backing.values["ai_ins_v3_item_en-US"])
        assertEquals("keep", backing.values["unrelated"])
        assertEquals(
            setOf("remove(ai_ins_v2_item_en-US)", "remove(ai_ins_v2_other)"),
            backing.calls.toSet(),
        )
    }

    @Test
    fun aKnownSlideWithAnEmptyLabelAndBodyIsDiscardedByTheParser() {
        val backing = RecordingKeyValueStore(
            mapOf(
                "ai_ins_v3_item_en-US" to
                    """{"slides":[{"key":"the_good_stuff","label":"   ","body":"   ","focus":"","context":"","accent":""}]}""",
            ),
        )
        val store = AiInsightsCacheStore(backing)

        assertNull(store.load("item", "en-US"))
    }

    private fun slide(
        key: AiInsightSlideKey = AiInsightSlideKey.THE_GOOD_STUFF,
        body: String? = "body",
        tag: AiInsightStandoutTag? = null,
        focus: String? = null,
        context: String? = null,
        backdrop: ResponsiveImageSet = ResponsiveImageSet(null, null, null),
        accent: String = "accent",
    ): AiInsightSlide = AiInsightSlide(
        key = key,
        label = "label",
        kind = AiInsightSlideKind.PROSE,
        body = body,
        tag = tag,
        focus = focus,
        context = context,
        backdrop = backdrop,
        accent = accent,
    )

}
