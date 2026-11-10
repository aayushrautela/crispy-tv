package com.crispy.tv.details

import com.crispy.tv.ai.AiInsightSlide
import com.crispy.tv.ai.AiInsightSlideKey
import com.crispy.tv.ai.AiInsightSlideKind
import com.crispy.tv.images.ResponsiveImageSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The story's three text slots: the heading is `label`, the headline is
 * `focus`, the paragraph is `body ?: context`. The heading and the headline
 * are separate lines now, so they are separate helpers; the paragraph is
 * still built from disjoint sources, so a slide can never print the same
 * sentence twice.
 */
class AiInsightStoryCopyTest {

    @Test
    fun labelAndFocusAreSeparateSlots() {
        val slide =
            slide(
                label = "Standout",
                focus = "Hiddleston's quiet portrayal of Chuck",
                body = "Hiddleston shifts from a quiet adult.",
            )

        assertEquals("Standout", slide.storyLabel())
        assertEquals("Hiddleston's quiet portrayal of Chuck", slide.storyFocus())
        assertEquals("Hiddleston shifts from a quiet adult.", slide.storyParagraph())
    }

    @Test
    fun withoutAFocusTheLabelStandsAlone() {
        val slide =
            slide(
                label = "The good stuff",
                focus = null,
                body = "A twelve-minute standing ovation.",
            )

        assertEquals("The good stuff", slide.storyLabel())
        assertNull(slide.storyFocus())
        assertEquals("A twelve-minute standing ovation.", slide.storyParagraph())
    }

    @Test
    fun aBlankFocusIsNoFocusSoTheLabelStillStandsAlone() {
        val slide = slide(label = "The catch", focus = "   ", body = "Sags in the middle.")

        assertEquals("The catch", slide.storyLabel())
        assertNull(slide.storyFocus())
        assertEquals("Sags in the middle.", slide.storyParagraph())
    }

    @Test
    fun withoutALabelTheFocusStandsAlone() {
        val slide = slide(label = "  ", focus = "A killer first act", body = "Ana carries it.")

        assertNull(slide.storyLabel())
        assertEquals("A killer first act", slide.storyFocus())
        assertEquals("Ana carries it.", slide.storyParagraph())
    }

    /** The rule the slots exist for: a slide never prints the same sentence twice. */
    @Test
    fun theSlotsNeverRepeatEachOther() {
        val cases =
            listOf(
                slide(label = "Standout", focus = "A killer first act", body = "Ana carries it.", context = null),
                slide(label = "The good stuff", focus = null, body = "Takes its time.", context = null),
                slide(label = "Trivia", focus = "Shot in order", body = null, context = "From the producer."),
                slide(label = "", focus = null, body = null, context = "Only context."),
                slide(label = "Standout", focus = "A killer first act", body = null, context = null),
                slide(label = "", focus = null, body = null, context = null),
            )

        cases.forEach { slide ->
            val printed =
                listOfNotNull(slide.storyLabel(), slide.storyFocus(), slide.storyParagraph())
            assertEquals(
                printed.size,
                printed.distinct().size,
                "printed the same text twice for label=${slide.label} focus=${slide.focus} body=${slide.body}",
            )
        }
    }

    @Test
    fun contextStandsInForAnAbsentBody() {
        val slide = slide(label = "Trivia", focus = null, body = null, context = "From the second act.")

        assertEquals("Trivia", slide.storyLabel())
        assertNull(slide.storyFocus())
        assertEquals("From the second act.", slide.storyParagraph())
    }

    @Test
    fun aSlideWithNothingToSayPrintsNothing() {
        val slide = slide(label = "  ", focus = "  ", body = null, context = null)

        assertNull(slide.storyLabel())
        assertNull(slide.storyFocus())
        assertNull(slide.storyParagraph())
    }

    private fun slide(
        label: String = "label",
        focus: String? = null,
        body: String? = null,
        context: String? = null,
    ): AiInsightSlide = AiInsightSlide(
        key = AiInsightSlideKey.THE_GOOD_STUFF,
        label = label,
        kind = AiInsightSlideKind.PROSE,
        body = body,
        tag = null,
        focus = focus,
        context = context,
        backdrop = ResponsiveImageSet(null, null, null),
        accent = "accent",
    )
}
