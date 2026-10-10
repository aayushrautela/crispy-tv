package com.crispy.tv.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The story header's subtitle: `year • label`, whichever halves exist.
 */
class StoryHeaderSubtitleTest {

    @Test
    fun yearAndLabelJoinWithAMiddot() {
        assertEquals("2013 • The good stuff", storyHeaderSubtitle(year = "2013", label = "The good stuff"))
    }

    @Test
    fun eitherHalfAloneStillPrints() {
        assertEquals("2013", storyHeaderSubtitle(year = "2013", label = "   "))
        assertEquals("Standout", storyHeaderSubtitle(year = null, label = "Standout"))
    }

    @Test
    fun blankInputsPrintNothing() {
        assertNull(storyHeaderSubtitle(year = "  ", label = ""))
        assertNull(storyHeaderSubtitle(year = null, label = "  "))
    }
}
