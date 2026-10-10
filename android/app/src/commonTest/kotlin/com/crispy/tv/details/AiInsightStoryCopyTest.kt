package com.crispy.tv.details

import com.crispy.tv.ai.AiInsightSlide
import com.crispy.tv.ai.AiInsightSlideKey
import com.crispy.tv.ai.AiInsightSlideKind
import com.crispy.tv.images.ResponsiveImageSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The story's two text slots, and the one rule that couples them: whatever
 * [storyHeadline] did not spend, [storyBody] gets.
 */
class AiInsightStoryCopyTest {

    @Test
    fun theHookIsTheFocusWhenTheServerSentOne() {
        val slide = slide(focus = "A killer first act", body = "Ana gives the film everything she has.")

        assertEquals("A killer first act", slide.storyHeadline())
        assertEquals("Ana gives the film everything she has.", slide.storyBody())
    }

    @Test
    fun withoutAFocusTheBodyBecomesTheHookAndIsNotRepeated() {
        val slide = slide(focus = null, body = "Takes its time and earns the ending.")

        assertEquals("Takes its time and earns the ending.", slide.storyHeadline())
        assertNull(slide.storyBody())
    }

    @Test
    fun aBlankFocusIsNoFocusSoTheBodyStillBecomesTheHook() {
        val slide = slide(focus = "   ", body = "Worth the wait.")

        assertEquals("Worth the wait.", slide.storyHeadline())
        assertNull(slide.storyBody())
    }

    /** The rule the slots exist for: a slide never prints the same sentence twice. */
    @Test
    fun whateverTheHookDidNotSpendIsWhatTheBodyPrints() {
        val cases =
            listOf(
                slide(focus = "A killer first act", body = "Ana carries it.", context = null),
                slide(focus = null, body = "Takes its time.", context = null),
                slide(focus = "A killer first act", body = null, context = "From the producer."),
                slide(focus = null, body = null, context = "Only context."),
                slide(focus = "A killer first act", body = null, context = null),
                slide(focus = null, body = null, context = null),
            )

        cases.forEach { slide ->
            val printed = listOfNotNull(slide.storyHeadline(), slide.storyBody())
            assertEquals(
                printed.size,
                printed.distinct().size,
                "printed the same text twice for focus=${slide.focus} body=${slide.body} context=${slide.context}",
            )
        }
    }

    @Test
    fun contextStandsInForAnAbsentBody() {
        val slide = slide(focus = null, body = null, context = "From the second act.")

        assertEquals("From the second act.", slide.storyHeadline())
        assertNull(slide.storyBody())
    }

    @Test
    fun aSlideWithNothingToSayPrintsNothing() {
        val slide = slide(focus = "  ", body = null, context = null)

        assertNull(slide.storyHeadline())
        assertNull(slide.storyBody())
    }

    private fun slide(
        focus: String? = null,
        body: String? = null,
        context: String? = null,
    ): AiInsightSlide = AiInsightSlide(
        key = AiInsightSlideKey.THE_GOOD_STUFF,
        label = "label",
        kind = AiInsightSlideKind.PROSE,
        body = body,
        tag = null,
        focus = focus,
        context = context,
        backdrop = ResponsiveImageSet(null, null, null),
        accent = "accent",
    )
}
