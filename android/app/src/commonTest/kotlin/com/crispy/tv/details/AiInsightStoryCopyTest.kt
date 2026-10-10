package com.crispy.tv.details

import com.crispy.tv.ai.AiInsightSlide
import com.crispy.tv.ai.AiInsightSlideKey
import com.crispy.tv.ai.AiInsightSlideKind
import com.crispy.tv.images.ResponsiveImageSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The story's two text slots: the title joins `label` to `focus` with a
 * middot, the paragraph is `body ?: context`. The two halves are built from
 * disjoint sources, so a slide can never print the same sentence twice.
 */
class AiInsightStoryCopyTest {

    @Test
    fun labelAndFocusJoinWithAMiddot() {
        val slide =
            slide(
                label = "Standout",
                focus = "Hiddleston's quiet portrayal of Chuck",
                body = "Hiddleston shifts from a quiet adult.",
            )

        assertEquals("Standout • Hiddleston's quiet portrayal of Chuck", slide.storyTitle())
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

        assertEquals("The good stuff", slide.storyTitle())
        assertEquals("A twelve-minute standing ovation.", slide.storyParagraph())
    }

    @Test
    fun aBlankFocusIsNoFocusSoTheLabelStillStandsAlone() {
        val slide = slide(label = "The catch", focus = "   ", body = "Sags in the middle.")

        assertEquals("The catch", slide.storyTitle())
        assertEquals("Sags in the middle.", slide.storyParagraph())
    }

    @Test
    fun withoutALabelTheFocusStandsAlone() {
        val slide = slide(label = "  ", focus = "A killer first act", body = "Ana carries it.")

        assertEquals("A killer first act", slide.storyTitle())
        assertEquals("Ana carries it.", slide.storyParagraph())
    }

    /** The rule the slots exist for: a slide never prints the same sentence twice. */
    @Test
    fun theTwoSlotsNeverRepeatEachOther() {
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
            val printed = listOfNotNull(slide.storyTitle(), slide.storyParagraph())
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

        assertEquals("Trivia", slide.storyTitle())
        assertEquals("From the second act.", slide.storyParagraph())
    }

    @Test
    fun aSlideWithNothingToSayPrintsNothing() {
        val slide = slide(label = "  ", focus = "  ", body = null, context = null)

        assertNull(slide.storyTitle())
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
