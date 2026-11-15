package com.crispy.tv.ai

import com.crispy.tv.images.ResponsiveImageSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The story's three text slots: the eyebrow is `label`, the headline is
 * `heading`, the paragraph is `body`. The eyebrow and the headline are
 * separate lines, so they are separate helpers; the paragraph is built from
 * a disjoint source, so a slide can never print the same sentence twice.
 */
class AiInsightStoryCopyTest {

    @Test
    fun labelAndHeadingAreSeparateSlots() {
        val slide =
            slide(
                label = "Standout",
                heading = "Hiddleston's quiet portrayal of Chuck",
                body = "Hiddleston shifts from a quiet adult.",
            )

        assertEquals("Standout", slide.storyLabel())
        assertEquals("Hiddleston's quiet portrayal of Chuck", slide.storyHeading())
        assertEquals("Hiddleston shifts from a quiet adult.", slide.storyParagraph())
    }

    @Test
    fun withoutAHeadingTheLabelStandsAlone() {
        val slide =
            slide(
                label = "The good stuff",
                heading = null,
                body = "A twelve-minute standing ovation.",
            )

        assertEquals("The good stuff", slide.storyLabel())
        assertNull(slide.storyHeading())
        assertEquals("A twelve-minute standing ovation.", slide.storyParagraph())
    }

    @Test
    fun aBlankHeadingIsNoHeadingSoTheLabelStillStandsAlone() {
        val slide = slide(label = "The catch", heading = "   ", body = "Sags in the middle.")

        assertEquals("The catch", slide.storyLabel())
        assertNull(slide.storyHeading())
        assertEquals("Sags in the middle.", slide.storyParagraph())
    }

    @Test
    fun withoutALabelTheHeadingStandsAlone() {
        val slide = slide(label = "  ", heading = "A killer first act", body = "Ana carries it.")

        assertNull(slide.storyLabel())
        assertEquals("A killer first act", slide.storyHeading())
        assertEquals("Ana carries it.", slide.storyParagraph())
    }

    /** The rule the slots exist for: a slide never prints the same sentence twice. */
    @Test
    fun theSlotsNeverRepeatEachOther() {
        val cases =
            listOf(
                slide(label = "Standout", heading = "A killer first act", body = "Ana carries it."),
                slide(label = "The good stuff", heading = null, body = "Takes its time."),
                slide(label = "Trivia", heading = "Shot in order", body = null),
                slide(label = "", heading = null, body = "Only body."),
                slide(label = "Standout", heading = "A killer first act", body = null),
                slide(label = "", heading = null, body = null),
            )

        cases.forEach { slide ->
            val printed =
                listOfNotNull(slide.storyLabel(), slide.storyHeading(), slide.storyParagraph())
            assertEquals(
                printed.size,
                printed.distinct().size,
                "printed the same text twice for label=${slide.label} heading=${slide.heading} body=${slide.body}",
            )
        }
    }

    @Test
    fun aSlideWithNothingToSayPrintsNothing() {
        val slide = slide(label = "  ", heading = "  ", body = null)

        assertNull(slide.storyLabel())
        assertNull(slide.storyHeading())
        assertNull(slide.storyParagraph())
    }

    private fun slide(
        label: String = "label",
        heading: String? = null,
        body: String? = null,
    ): AiInsightSlide = AiInsightSlide(
        key = AiInsightSlideKey.THE_GOOD_STUFF,
        label = label,
        kind = AiInsightSlideKind.PROSE,
        heading = heading,
        body = body,
        backdrop = ResponsiveImageSet(null, null, null),
        accent = "accent",
    )
}
