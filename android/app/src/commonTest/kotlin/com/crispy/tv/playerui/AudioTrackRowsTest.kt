package com.crispy.tv.playerui

import com.crispy.tv.nativeengine.playback.NativeTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the two lines of an audio-track row and the codec summary under them.
 *
 * The interesting half is the *absence* rules. A row is built from a title, a
 * language and whatever the engine volunteered about codec and channels, and each
 * of the four is optional on its own -- so nearly every case here is one part
 * missing, and a mistake reads as a row that says something wrong rather than one
 * that stops rendering. The channel count is pinned as a count (`6ch`) and never a
 * layout (`5.1`), because that is the only thing the engines report.
 */
class AudioTrackRowsTest {

    private fun track(
        id: String = "t1",
        language: String? = null,
        title: String? = null,
        codec: String? = null,
        channelCount: Int? = null,
    ) = NativeTrack(id = id, index = 0, language = language, title = title, codec = codec, channelCount = channelCount)

    // region codec and channels

    @Test
    fun `no codec and no channels is no second line`() {
        assertNull(audioCodecAndChannels(codec = null, channelCount = null))
    }

    @Test
    fun `a bare mpv codec is uppercased`() {
        assertEquals("EAC3", audioCodecAndChannels(codec = "eac3", channelCount = null))
    }

    @Test
    fun `the older ec-3 spelling keeps its hyphen`() {
        assertEquals("EC-3", audioCodecAndChannels(codec = "ec-3", channelCount = null))
    }

    @Test
    fun `an exo mime type loses its audio prefix`() {
        assertEquals("EAC3", audioCodecAndChannels(codec = "audio/eac3", channelCount = null))
    }

    @Test
    fun `channels alone render as a count`() {
        assertEquals("6ch", audioCodecAndChannels(codec = null, channelCount = 6))
    }

    @Test
    fun `codec and channels are joined`() {
        assertEquals("EAC3 • 6ch", audioCodecAndChannels(codec = "eac3", channelCount = 6))
    }

    @Test
    fun `a blank codec is not a codec`() {
        assertEquals("6ch", audioCodecAndChannels(codec = "   ", channelCount = 6))
    }

    @Test
    fun `a zero channel count is not a channel count`() {
        assertEquals("OPUS", audioCodecAndChannels(codec = "opus", channelCount = 0))
    }

    @Test
    fun `a mixed case codec still matches the shared spelling`() {
        assertEquals("AAC • 2ch", audioCodecAndChannels(codec = "Aac", channelCount = 2))
    }

    // endregion

    // region name line

    @Test
    fun `a real title is the name`() {
        assertEquals("Commentary", audioTrackName(track(title = "Commentary"), languageName = "English"))
    }

    @Test
    fun `the language name is the name when the track has no title`() {
        assertEquals("English", audioTrackName(track(language = "eng"), languageName = "English"))
    }

    @Test
    fun `a blank title is not a title`() {
        assertEquals("English", audioTrackName(track(title = "  "), languageName = "English"))
    }

    // endregion

    // region second line

    @Test
    fun `an untitled track with no codec has no second line`() {
        assertNull(audioTrackSubtitle(track(language = "eng"), languageName = "English"))
    }

    @Test
    fun `an untitled track shows codec and channels`() {
        assertEquals(
            "EAC3 • 6ch",
            audioTrackSubtitle(track(language = "eng", codec = "eac3", channelCount = 6), languageName = "English"),
        )
    }

    @Test
    fun `a titled track shows its language before the codec`() {
        assertEquals(
            "English • EAC3 • 6ch",
            audioTrackSubtitle(track(language = "eng", title = "Commentary", codec = "eac3", channelCount = 6), "English"),
        )
    }

    @Test
    fun `a titled track with no codec keeps its language line`() {
        assertEquals("English", audioTrackSubtitle(track(language = "eng", title = "Commentary"), languageName = "English"))
    }

    @Test
    fun `an absent language is not repeated for a titled track`() {
        assertEquals("AAC", audioTrackSubtitle(track(language = null, title = "Commentary", codec = "aac"), languageName = "Unknown"))
    }

    @Test
    fun `an undetermined language is not repeated for a titled track`() {
        assertEquals(
            "AAC",
            audioTrackSubtitle(track(language = "und", title = "Commentary", codec = "aac"), languageName = "Undetermined"),
        )
    }

    // endregion
}
