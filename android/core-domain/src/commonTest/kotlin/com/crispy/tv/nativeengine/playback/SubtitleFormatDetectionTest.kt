package com.crispy.tv.nativeengine.playback

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The format-detection rules the sidecar parser and the SSA/libass carve-out
 * both read. The interesting property is precedence: the bytes beat the URL,
 * because subtitle addons routinely serve extensionless or mislabeled URLs.
 *
 * Real-world shape of the fixtures: an OpenSubtitles-style SRT body starts
 * with the cue index `1` alone on a line, and a WebVTT body starts with the
 * `WEBVTT` header, so "bytes say X, URL says Y" cases are the ones that cost
 * users a silent failure when the URL is trusted over the body.
 */
class SubtitleFormatDetectionTest {
    @Test
    fun `url hint maps known extensions`() {
        assertEquals(SubtitleContainerFormat.SubRip, subtitleFormatHintFromUrl("https://cdn.example/subs/movie.srt"))
        assertEquals(SubtitleContainerFormat.WebVtt, subtitleFormatHintFromUrl("https://cdn.example/subs/movie.vtt"))
        assertEquals(SubtitleContainerFormat.WebVtt, subtitleFormatHintFromUrl("https://cdn.example/subs/movie.webvtt"))
        assertEquals(SubtitleContainerFormat.SubStationAlpha, subtitleFormatHintFromUrl("https://cdn.example/subs/movie.ass"))
        assertEquals(SubtitleContainerFormat.SubStationAlpha, subtitleFormatHintFromUrl("https://cdn.example/subs/movie.ssa"))
        assertEquals(SubtitleContainerFormat.Ttml, subtitleFormatHintFromUrl("https://cdn.example/subs/movie.ttml"))
        assertEquals(SubtitleContainerFormat.Ttml, subtitleFormatHintFromUrl("https://cdn.example/subs/movie.dfxp"))
        assertEquals(SubtitleContainerFormat.Ttml, subtitleFormatHintFromUrl("https://cdn.example/subs/movie.xml"))
    }

    @Test
    fun `url hint ignores query fragment trailing slash and case`() {
        assertEquals(SubtitleContainerFormat.SubRip, subtitleFormatHintFromUrl("https://cdn.example/subs/MOVIE.SRT"))
        assertEquals(SubtitleContainerFormat.SubRip, subtitleFormatHintFromUrl("https://cdn.example/subs/movie.srt?dl=1&lang=en"))
        assertEquals(SubtitleContainerFormat.WebVtt, subtitleFormatHintFromUrl("https://cdn.example/subs/movie.vtt#t=0"))
        assertEquals(null, subtitleFormatHintFromUrl("https://cdn.example/subs/"))
        assertEquals(null, subtitleFormatHintFromUrl("https://cdn.example/subs/download?id=42"))
    }

    @Test
    fun `url format falls back to subrip when the url says nothing`() {
        assertEquals(SubtitleContainerFormat.SubRip, subtitleFormatFromUrl("https://cdn.example/subs/267935"))
        assertEquals(SubtitleContainerFormat.SubRip, subtitleFormatFromUrl(""))
    }

    @Test
    fun `sniff prefers the body over the url`() {
        val srtLongAsVttUrl = "$SUBRIP_BODY"
        assertEquals(
            SubtitleContainerFormat.SubRip,
            sniffSubtitleFormat(srtLongAsVttUrl, "https://cdn.example/subs/movie.vtt"),
            "SRT body behind a .vtt URL must parse as SubRip; the WebVTT parser rejects the leading cue index",
        )
        assertEquals(
            SubtitleContainerFormat.WebVtt,
            sniffSubtitleFormat(WEBVTT_BODY, "https://cdn.example/subs/movie.srt"),
            "WebVTT body behind a .srt URL must parse as WebVTT",
        )
    }

    @Test
    fun `sniff accepts webvtt with bom and leading whitespace`() {
        assertEquals(SubtitleContainerFormat.WebVtt, sniffSubtitleFormat("\uFEFF$WEBVTT_BODY"))
        assertEquals(SubtitleContainerFormat.WebVtt, sniffSubtitleFormat("\n  $WEBVTT_BODY"))
    }

    @Test
    fun `sniff detects subrip from timestamps without a cue index`() {
        assertEquals(
            SubtitleContainerFormat.SubRip,
            sniffSubtitleFormat("00:00:02,400 --> 00:00:04,100\nHello\n"),
            "a bare timestamp line is SubRip even without the numbered cue",
        )
    }

    @Test
    fun `sniff detects ssa and ttml by structural markers`() {
        assertEquals(
            SubtitleContainerFormat.SubStationAlpha,
            sniffSubtitleFormat("[Script Info]\nTitle: fixture\n[V4+ Styles]\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello"),
        )
        assertEquals(
            SubtitleContainerFormat.Ttml,
            sniffSubtitleFormat("<?xml version=\"1.0\" encoding=\"utf-8\"?><tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>"),
        )
    }

    @Test
    fun `sniff falls back to the url for unrecognized bodies`() {
        assertEquals(
            SubtitleContainerFormat.WebVtt,
            sniffSubtitleFormat("", "https://cdn.example/subs/movie.vtt"),
            "an empty body cannot be sniffed, so the URL decides",
        )
        assertEquals(
            SubtitleContainerFormat.SubRip,
            sniffSubtitleFormat("<html><body>404</body></html>", "https://cdn.example/subs/267935"),
            "an HTML error page is not a cue file; the URL default stands",
        )
    }

    @Test
    fun `candidates are sniffed first then url then the full set without duplicates`() {
        val fromSrtBody = subtitleFormatCandidates(SUBRIP_BODY, "https://cdn.example/subs/movie.vtt")
        assertEquals(
            SubtitleContainerFormat.SubRip,
            fromSrtBody.first(),
            "the sniffed answer must be tried before the URL-derived one",
        )
        assertEquals(
            listOf(
                SubtitleContainerFormat.SubRip,
                SubtitleContainerFormat.WebVtt,
                SubtitleContainerFormat.SubStationAlpha,
                SubtitleContainerFormat.Ttml,
            ),
            fromSrtBody,
            "all four formats must be candidates exactly once, in fallback order",
        )

        val fromExtensionlessBody = subtitleFormatCandidates(WEBVTT_BODY, "https://cdn.example/subs/267935")
        assertEquals(SubtitleContainerFormat.WebVtt, fromExtensionlessBody.first())
        assertEquals(
            setOf(
                SubtitleContainerFormat.SubRip,
                SubtitleContainerFormat.WebVtt,
                SubtitleContainerFormat.SubStationAlpha,
                SubtitleContainerFormat.Ttml,
            ),
            fromExtensionlessBody.toSet(),
        )
        assertEquals(4, fromExtensionlessBody.size, "sniffed and URL entries that coincide must dedupe")
    }

    @Test
    fun `adjustableInCroppedArea is true only for plain text formats`() {
        assertEquals(true, SubtitleContainerFormat.SubRip.adjustableInCroppedArea)
        assertEquals(true, SubtitleContainerFormat.WebVtt.adjustableInCroppedArea)
        assertEquals(
            false,
            SubtitleContainerFormat.SubStationAlpha.adjustableInCroppedArea,
            "styled subs carry authored placement and must not be moved",
        )
        assertEquals(
            false,
            SubtitleContainerFormat.Ttml.adjustableInCroppedArea,
            "TTML positions itself; the runtime must not reposition it",
        )
    }

    @Test
    fun `embedded codec is adjustable only for subrip and webvtt`() {
        assertEquals(true, isAdjustableEmbeddedSubtitleCodec("subrip"))
        assertEquals(true, isAdjustableEmbeddedSubtitleCodec("webvtt"))
        assertEquals(true, isAdjustableEmbeddedSubtitleCodec("SubRip"), "codec names must be case-insensitive")
        assertEquals(true, isAdjustableEmbeddedSubtitleCodec("WEBVTT"), "codec names must be case-insensitive")

        val untouched =
            listOf("ass", "ssa", "ttml", "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "mov_text", "")
        for (codec in untouched) {
            assertEquals(
                false,
                isAdjustableEmbeddedSubtitleCodec(codec),
                "codec \"$codec\" must stay untouched under the crop",
            )
        }
    }

    @Test
    fun `codec answer wins over the url hint`() {
        assertEquals(
            true,
            isAdjustableSubtitle(codec = "subrip", externalUrl = "https://cdn.example/subs/movie.ass"),
            "a demuxed subrip track must be movable even if the URL claims an ASS file",
        )
        assertEquals(
            false,
            isAdjustableSubtitle(codec = "hdmv_pgs_subtitle", externalUrl = "https://cdn.example/subs/movie.srt"),
            "a demuxed bitmap track must stay put even if the URL claims an SRT file",
        )
    }

    @Test
    fun `url hint is the fallback when the codec is missing`() {
        assertEquals(true, isAdjustableSubtitle(codec = null, externalUrl = "https://cdn.example/subs/movie.srt"))
        assertEquals(true, isAdjustableSubtitle(codec = null, externalUrl = "https://cdn.example/subs/movie.vtt"))
        assertEquals(
            false,
            isAdjustableSubtitle(codec = null, externalUrl = "https://cdn.example/subs/movie.ass"),
            "an ASS URL must not be repositioned",
        )
        assertEquals(
            false,
            isAdjustableSubtitle(codec = null, externalUrl = "https://cdn.example/subs/download?id=42"),
            "an extensionless URL carries no format signal, so the track stays untouched",
        )
        assertEquals(
            false,
            isAdjustableSubtitle(codec = null, externalUrl = null),
            "no codec and no URL means nothing to go on, so the track stays untouched",
        )
    }

    private companion object {
        const val WEBVTT_BODY = "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHello world\n"
        const val SUBRIP_BODY = "1\n00:00:02,000 --> 00:00:04,500\nHello world\n\n2\n00:00:05,000 --> 00:00:07,000\nAgain\n"
    }
}
