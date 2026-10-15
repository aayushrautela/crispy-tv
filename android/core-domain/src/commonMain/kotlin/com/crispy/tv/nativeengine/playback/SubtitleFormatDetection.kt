package com.crispy.tv.nativeengine.playback

/**
 * Container format of an external subtitle file, decided without any platform
 * text-decoding machinery so the same rules apply on every target.
 *
 * The subtitle stack has two format questions and they must not be conflated:
 *
 * - What the *URL claims* — [subtitleFormatHintFromUrl], an informed guess from
 *   the path extension. Subtitle addons routinely serve extensionless URLs, so
 *   this guess is genuinely a guess.
 * - What the *bytes say* — [sniffSubtitleFormat], decided from the file body.
 *   A wrong declaration here is a whole-class failure for the engine-side
 *   parser, and the proof is a WebVTT parser being handed an SRT file ("Got 1":
 *   the first cue index of a SubRip stream).
 */
enum class SubtitleContainerFormat {
    SubRip,
    WebVtt,
    SubStationAlpha,
    Ttml,
}

/**
 * Whether the runtime may move this format's cues into the letterbox margins
 * (mpv's `sub-use-margins`) to keep them visible while `panscan` crops the
 * video frame. Only plain-text formats qualify: styled (ASS/SSA) subs carry
 * authored placement we must not override, TTML similarly positions itself,
 * and any unlisted format is treated as untouchable by default.
 */
val SubtitleContainerFormat.adjustableInCroppedArea: Boolean
    get() = this == SubtitleContainerFormat.SubRip || this == SubtitleContainerFormat.WebVtt

/**
 * Whether mpv's `track-list/N/codec` string denotes an embedded subtitle track
 * whose placement mpv allows us to move (into the margins, via `sub-pos`).
 * Plain text containers (subrip, webvtt) are movable; styled (ass, ttml) and
 * bitmap (pgs, dvd, dvb) tracks are left alone, as is anything unrecognized.
 */
fun isAdjustableEmbeddedSubtitleCodec(codec: String): Boolean =
    when (codec.lowercase()) {
        "subrip", "webvtt" -> true
        else -> false
    }

/**
 * Decision for whether the currently selected subtitle may be moved into the
 * margins while `panscan` crops the video frame: answer from the demuxer
 * codec when mpv reports one, else fall back to the external-file URL hint.
 * Anything unrecognized (including bitmap tracks, which report codecs like
 * `hdmv_pgs_subtitle`) stays put.
 */
fun isAdjustableSubtitle(codec: String?, externalUrl: String?): Boolean =
    codec?.let(::isAdjustableEmbeddedSubtitleCodec)
        ?: externalUrl?.let(::subtitleFormatHintFromUrl)?.adjustableInCroppedArea
        ?: false

/**
 * Format derived from the URL alone, `null` when the URL carries no usable
 * signal. Kept nullable so a caller can ask "what does the URL claim" without
 * inheriting the SubRip fallback that [subtitleFormatFromUrl] bakes in.
 */
fun subtitleFormatHintFromUrl(url: String): SubtitleContainerFormat? {
    val normalizedPath =
        url
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .lowercase()
    val extension = normalizedPath.substringAfterLast('.', missingDelimiterValue = "")
    return when (extension) {
        "srt" -> SubtitleContainerFormat.SubRip
        "vtt", "webvtt" -> SubtitleContainerFormat.WebVtt
        "ass", "ssa" -> SubtitleContainerFormat.SubStationAlpha
        "ttml", "dfxp" -> SubtitleContainerFormat.Ttml
        "xml" -> SubtitleContainerFormat.Ttml
        else -> null
    }
}

/**
 * Format derived from the URL alone, falling back to SubRip when the URL says
 * nothing. SubRip is the compostable default: it is what the dominant subtitle
 * addons actually serve behind extensionless URLs, and a SubRip file that is
 * secretly something else gets a second chance through [sniffSubtitleFormat]
 * once the body is in hand.
 */
fun subtitleFormatFromUrl(url: String): SubtitleContainerFormat =
    subtitleFormatHintFromUrl(url) ?: SubtitleContainerFormat.SubRip

/**
 * Format decided from the file body, with the URL as the tiebreaker when the
 * body matches nothing. Order matters: WebVTT's header check is unambiguous,
 * SSA and TTML have structural markers, and SubRip is proven by a numbered cue
 * with a timestamp line — the strongest signal of the four.
 */
fun sniffSubtitleFormat(rawText: String, sourceUrl: String = ""): SubtitleContainerFormat {
    val text = rawText.replace("\uFEFF", "").trimStart()
    if (text.isEmpty()) return subtitleFormatFromUrl(sourceUrl)

    if (text.startsWith("WEBVTT", ignoreCase = true)) {
        return SubtitleContainerFormat.WebVtt
    }

    val head = text.take(4_000)
    if (
        head.startsWith("[Script Info]", ignoreCase = true) ||
        head.contains("[V4+ Styles]", ignoreCase = true) ||
        head.contains("[V4 Styles]", ignoreCase = true) ||
        SSA_DIALOGUE_PATTERN.containsMatchIn(head)
    ) {
        return SubtitleContainerFormat.SubStationAlpha
    }

    val lowerHead = head.lowercase()
    if (
        (lowerHead.startsWith("<?xml") || lowerHead.contains("<tt ")) &&
        (lowerHead.contains("ttml") || lowerHead.contains(":tt") || lowerHead.contains("<tt "))
    ) {
        return SubtitleContainerFormat.Ttml
    }

    if (
        SUBRIP_NUMBERED_CUE_PATTERN.containsMatchIn(text.take(800)) ||
        SUBRIP_TIMESTAMP_PATTERN.containsMatchIn(text.take(400))
    ) {
        return SubtitleContainerFormat.SubRip
    }

    return subtitleFormatFromUrl(sourceUrl)
}

/**
 * The formats a robust parser should attempt, in order: what the bytes say,
 * then what the URL claims, then the full set as a last resort. Deduped with
 * order preserved, so the caller can walk the list trying each parser.
 */
fun subtitleFormatCandidates(
    rawText: String,
    sourceUrl: String,
): List<SubtitleContainerFormat> =
    linkedSetOf(
        sniffSubtitleFormat(rawText, sourceUrl),
        subtitleFormatFromUrl(sourceUrl),
        SubtitleContainerFormat.SubRip,
        SubtitleContainerFormat.WebVtt,
        SubtitleContainerFormat.SubStationAlpha,
        SubtitleContainerFormat.Ttml,
    ).toList()

private val SSA_DIALOGUE_PATTERN = Regex("""(?im)^\s*Dialogue:""")

private val SUBRIP_NUMBERED_CUE_PATTERN =
    Regex("""(?m)^\d+\s*\r?\n\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}""")

private val SUBRIP_TIMESTAMP_PATTERN =
    Regex("""(?m)^\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}""")
