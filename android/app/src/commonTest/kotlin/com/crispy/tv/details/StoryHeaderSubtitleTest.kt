package com.crispy.tv.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The story header's subtitle: the year, full stop. The slide label left the
 * header for the copy block, where it prints as the title line.
 */
class StoryHeaderSubtitleTest {

    @Test
    fun aYearPrints() {
        assertEquals("2013", storyHeaderSubtitle(year = "2013"))
    }

    @Test
    fun blankInputsPrintNothing() {
        assertNull(storyHeaderSubtitle(year = "  "))
        assertNull(storyHeaderSubtitle(year = null))
    }
}
