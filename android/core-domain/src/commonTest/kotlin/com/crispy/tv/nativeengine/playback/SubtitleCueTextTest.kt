package com.crispy.tv.nativeengine.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Cases for [sanitizeSubtitleCue]. Each fixture is chosen so the raw and the
 * sanitised answer differ in exactly the respect the case names: a markup-only
 * cue must answer `null` (an invisible box is still a box), and an escape like
 * `\N` must become a real newline rather than two characters a `Text` would print.
 */
class SubtitleCueTextTest {

    @Test
    fun blankInputAnswersNull() {
        assertNull(sanitizeSubtitleCue(""))
        assertNull(sanitizeSubtitleCue("   "))
        assertNull(sanitizeSubtitleCue(" \n "))
    }

    @Test
    fun plainTextIsTrimmedAndPassesThrough() {
        assertEquals("Hello", sanitizeSubtitleCue("  Hello  "))
        assertEquals("one\ntwo", sanitizeSubtitleCue("one\ntwo"))
    }

    @Test
    fun assOverrideBlocksAreStripped() {
        assertEquals("Hello world", sanitizeSubtitleCue("{\\i1\\b1}Hello {\\i0\\b0}world"))
        assertEquals("a b", sanitizeSubtitleCue("{\\pos(10,20)}a{\\move(1,2,3,4)} b"))
    }

    @Test
    fun markupTagsAreStripped() {
        assertEquals("Hello", sanitizeSubtitleCue("<i>Hello</i>"))
        assertEquals("Hello", sanitizeSubtitleCue("<font color=\"#FFFFFF\">Hello</font>"))
        assertEquals("a b", sanitizeSubtitleCue("<b>a</b> <u>b</u>"))
    }

    @Test
    fun assLineBreaksBecomeNewlines() {
        assertEquals("one\ntwo", sanitizeSubtitleCue("one\\Ntwo"))
        assertEquals("one\ntwo", sanitizeSubtitleCue("one\\ntwo"))
    }

    @Test
    fun hardSpacesBecomeSpaces() {
        assertEquals("a b", sanitizeSubtitleCue("a\\hb"))
    }

    @Test
    fun aCueThatWasOnlyMarkupAnswersNull() {
        assertNull(sanitizeSubtitleCue("{\\i1\\b1}"))
        assertNull(sanitizeSubtitleCue("<i></i>"))
        assertNull(sanitizeSubtitleCue("{\\i1}\\N"))
    }

    @Test
    fun blankLinesInsideACueAreDropped() {
        assertEquals("one\ntwo", sanitizeSubtitleCue("one\\N\\Ntwo"))
        assertEquals("one\ntwo", sanitizeSubtitleCue("  one  \\N  two  "))
    }
}
