package com.crispy.tv.nativeengine.playback

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ExoSidecarSubs"
private const val SIDECAR_RENDER_INTERVAL_MS = 100L
private const val SIDECAR_MAX_BODY_BYTES = 2 * 1024 * 1024

/**
 * Exo-path renderer for remote add-on subtitles that never touches the media
 * pipeline.
 *
 * Attaching a subtitle here downloads its body (with per-subtitle headers),
 * sniffs the container from the bytes, parses cues with the bundled lenient
 * SRT/VTT parser, and draws them onto the bound [SubtitleView] on a 100 ms
 * position loop. The media item is never rebuilt, so the buffer survives every
 * pick -- a failure only clears the (never-set) selection and logs.
 *
 * Deliberately narrower than a general subtitle stack:
 *
 * - SubRip and WebVTT go through the sidecar. Their URL hint decides
 *   admission ([canAttach]); the sniffed body can still veto when the URL lied
 *   (an SSA or TTML body behind an extensionless URL aborts silently).
 * - SSA/ASS goes through the media item, where it keeps the styling a cue
 *   list cannot carry (the libass renderer when on, Exo's own SSA decoder
 *   when off).
 * - TTML goes through the media item too: this parser has no TTML grammar, and
 *   the media item already declares the right MIME for `.ttml` URLs.
 */
@UnstableApi
internal class ExoSidecarSubtitleController(
    private val scope: CoroutineScope,
    private val getPlayer: () -> Player?,
    private val getSubtitleDelayMs: () -> Int = { 0 },
) {
    private var sidecarJob: Job? = null
    private var generationCounter = 0L

    /** URL of the attached subtitle, or null when the sidecar holds nothing. */
    var activeSubtitleUrl: String? = null
        private set
    private var activeGeneration = 0L
    private var timedCues: List<SidecarCue> = emptyList()
    private var lastCueSignature: Int? = null
    private var subtitleViewRef: WeakReference<SubtitleView>? = null

    /** Stable `ext:<hash>` id of the attachment, for snapshot reporting. */
    val activeTrackId: String?
        get() = activeSubtitleUrl?.let(::externalSubtitleTrackId)

    fun isActive(): Boolean = activeSubtitleUrl != null

    fun isActiveUrl(url: String): Boolean = activeSubtitleUrl == url

    /**
     * Admission gate, decided from the URL alone because the body is not in
     * hand yet. Only the two cue-list formats come here: SSA/ASS keeps the
     * styling a cue list cannot carry (libass when on, Exo's own SSA decoder
     * when off), and TTML keeps the media item's correct MIME for `.ttml`
     * URLs. This parser has no grammar for either.
     */
    fun canAttach(url: String): Boolean {
        return when (subtitleFormatFromUrl(url)) {
            SubtitleContainerFormat.SubRip,
            SubtitleContainerFormat.WebVtt,
            -> true
            SubtitleContainerFormat.SubStationAlpha,
            SubtitleContainerFormat.Ttml,
            -> false
        }
    }

    fun bindSubtitleView(subtitleView: SubtitleView?) {
        subtitleViewRef = subtitleView?.let(::WeakReference)
        if (activeSubtitleUrl != null && timedCues.isNotEmpty()) {
            renderAtCurrentPosition()
        }
    }

    fun stop(clearView: Boolean = true) {
        sidecarJob?.cancel()
        sidecarJob = null
        activeSubtitleUrl = null
        activeGeneration = 0L
        timedCues = emptyList()
        lastCueSignature = null
        if (clearView) {
            postToSubtitleView { it.setCues(emptyList()) }
        }
    }

    /**
     * Attaches [subtitle] without touching the player. Returns false when the
     * URL is not sidecar-admissible (caller falls back to the media item) or
     * when it is already the active attachment (no re-download).
     */
    fun start(subtitle: PlaybackExternalSubtitle): Boolean {
        if (!canAttach(subtitle.url)) return false
        if (isActiveUrl(subtitle.url) && timedCues.isNotEmpty()) return true

        val key = subtitle.url
        sidecarJob?.cancel()
        val generation = ++generationCounter
        activeGeneration = generation
        activeSubtitleUrl = key
        lastCueSignature = null
        timedCues = emptyList()
        postToSubtitleView { it.setCues(emptyList()) }
        fun isCurrent() = activeSubtitleUrl == key && activeGeneration == generation

        sidecarJob = scope.launch {
            try {
                val rawBody = withContext(Dispatchers.IO) {
                    downloadSubtitleText(url = key, headers = subtitle.headers)
                }
                if (!isCurrent()) return@launch

                val sniffed = sniffSubtitleFormat(rawBody, key)
                if (sniffed != SubtitleContainerFormat.SubRip && sniffed != SubtitleContainerFormat.WebVtt) {
                    Log.w(
                        TAG,
                        "Sidecar vetoed url=$key: body sniffs as $sniffed, which renders via the media item, not cues " +
                            "(buffer preserved; no media reload)",
                    )
                    deactivate(key, generation)
                    return@launch
                }
                val parsed = withContext(Dispatchers.Default) {
                    SidecarSubtitleParser.parse(rawBody)
                }
                if (!isCurrent()) return@launch

                if (parsed.isEmpty()) {
                    Log.w(
                        TAG,
                        "Sidecar parse empty url=$key sniffed=$sniffed (buffer preserved; no media reload)",
                    )
                    deactivate(key, generation)
                    return@launch
                }

                timedCues = parsed
                Log.d(TAG, "Sidecar ready url=$key cues=${parsed.size} sniffed=$sniffed (buffer preserved)")
                while (isActive && isCurrent()) {
                    renderAtCurrentPosition()
                    delay(SIDECAR_RENDER_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent()) return@launch
                Log.w(TAG, "Sidecar failed url=$key: ${e.message} (buffer preserved; no media reload)")
                deactivate(key, generation)
            }
        }
        return true
    }

    fun renderAtCurrentPosition() {
        val cues = timedCues
        val key = activeSubtitleUrl
        if (cues.isEmpty() || key == null) return
        val player = getPlayer() ?: return
        val delayUs = getSubtitleDelayMs().toLong() * 1_000L
        val positionUs = (player.currentPosition.coerceAtLeast(0L) * 1_000L - delayUs).coerceAtLeast(0L)
        val active = cues.filter { positionUs in it.startUs until it.endUs }
        val signature = activeSignature(active)
        if (signature == lastCueSignature) return
        lastCueSignature = signature
        val generationAtPost = activeGeneration
        val rendered =
            active.map { cue ->
                Cue.Builder().setText(cue.text).build()
            }
        postToSubtitleView { view ->
            if (activeSubtitleUrl == key && activeGeneration == generationAtPost) {
                view.setCues(rendered)
            }
        }
    }

    private fun deactivate(key: String, generation: Long) {
        if (activeSubtitleUrl == key && activeGeneration == generation) {
            activeSubtitleUrl = null
            activeGeneration = 0L
        }
        timedCues = emptyList()
        lastCueSignature = null
        postToSubtitleView { view ->
            if (activeSubtitleUrl == null) {
                view.setCues(emptyList())
            }
        }
    }

    private fun activeSignature(active: List<SidecarCue>): Int {
        var hash = 17
        active.forEach { cue ->
            hash = 31 * hash + cue.startUs.hashCode()
            hash = 31 * hash + cue.endUs.hashCode()
            hash = 31 * hash + cue.text.hashCode()
        }
        return hash
    }

    private fun postToSubtitleView(block: (SubtitleView) -> Unit) {
        val view = subtitleViewRef?.get() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block(view)
        } else {
            mainHandler.post { subtitleViewRef?.get()?.let(block) }
        }
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())

        @Throws(Exception::class)
        private fun downloadSubtitleText(url: String, headers: Map<String, String>): String {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw IllegalArgumentException("sidecar needs an http(s) subtitle url")
            }
            val connection =
                CrispyPlaybackNetworking.openConnection(
                    url = url,
                    headers = headers,
                    method = "GET",
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 15_000,
                )
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("subtitle GET failed http=$code")
                }
                val declaredCharset =
                    connection.contentType
                        ?.split(';')
                        ?.firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
                        ?.substringAfter('=')
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                if (declaredCharset != null && !declaredCharset.equals(Charsets.UTF_8.name(), ignoreCase = true)) {
                    Log.d(TAG, "Sidecar declared charset=$declaredCharset; body read as UTF-8")
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val out = StringBuilder()
                    val buf = CharArray(8_192)
                    var total = 0
                    while (true) {
                        val read = reader.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > SIDECAR_MAX_BODY_BYTES) {
                            throw IllegalStateException("subtitle body exceeds $SIDECAR_MAX_BODY_BYTES chars")
                        }
                        out.append(buf, 0, read)
                    }
                    return out.toString()
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}

/** One parsed cue: microsecond range plus display text. */
internal data class SidecarCue(
    val startUs: Long,
    val endUs: Long,
    val text: String,
)

/**
 * Lenient SubRip/WebVTT parser. One grammar for both, because the only
 * structural differences (the `WEBVTT` header, `NOTE`/`STYLE`/`REGION` blocks,
 * `.` vs `,` millis, missing cue numbers) are all skippable or acceptable in a
 * block scan: split on blank lines, find the timestamp line, take the rest as
 * text. Anything that is neither (SSA `Dialogue:`, TTML `<p>`) yields no cues,
 * which the controller reports as an empty parse rather than a misrender.
 */
internal object SidecarSubtitleParser {
    private val TIMESTAMP_LINE =
        Regex(
            """(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3}).*""",
        )
    private val TAG_STRIP = Regex("""<[^>]*>""")

    fun parse(rawText: String): List<SidecarCue> {
        val cleaned = rawText.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n')
        if (cleaned.isBlank()) return emptyList()
        val cues = ArrayList<SidecarCue>()
        cleaned.split("\n\n").forEach { block ->
            parseBlock(block)?.let(cues::add)
        }
        return cues.sortedBy { it.startUs }
    }

    private fun parseBlock(block: String): SidecarCue? {
        val lines = block.lines().map { it.trimEnd() }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return null
        // WebVTT header, notes, styles and regions are metadata, not cues.
        val first = lines.first().trim()
        if (first.startsWith("WEBVTT", ignoreCase = true)) return null
        if (first.startsWith("NOTE", ignoreCase = true)) return null
        if (first.equals("STYLE", ignoreCase = true) || first.equals("REGION", ignoreCase = true)) return null

        var cursor = 0
        // Optional cue identifier: a numeric SubRip index, or a WebVTT cue
        // identifier line above the timing. Either way the timestamp line is
        // the first or second line; anything deeper is not a cue block.
        if (lines.size > 1 && TIMESTAMP_LINE.matchEntire(lines[0].trim()) == null &&
            TIMESTAMP_LINE.matchEntire(lines[1].trim()) != null
        ) {
            cursor = 1
        }
        if (cursor >= lines.size) return null
        val timing = TIMESTAMP_LINE.matchEntire(lines[cursor].trim()) ?: return null
        val startUs = toUs(timing, offset = 0) ?: return null
        val endUs = toUs(timing, offset = 4) ?: return null
        if (endUs <= startUs) return null
        val text =
            lines.drop(cursor + 1).joinToString("\n") { cleanCueText(it.trim()) }.trim()
        if (text.isBlank()) return null
        return SidecarCue(startUs = startUs, endUs = endUs, text = text)
    }

    private fun toUs(match: MatchResult, offset: Int): Long? {
        val hours = match.groups[offset + 1]?.value?.toLongOrNull() ?: 0L
        val minutes = match.groups[offset + 2]?.value?.toLongOrNull() ?: return null
        val seconds = match.groups[offset + 3]?.value?.toLongOrNull() ?: return null
        val millisRaw = match.groups[offset + 4]?.value ?: return null
        // One to three fraction digits, right-padded: ".5" is 500 ms, ".05" is 50 ms.
        val millis = millisRaw.padEnd(3, '0').toLongOrNull() ?: return null
        if (minutes >= 60 || seconds >= 60) return null
        return ((hours * 3_600L + minutes * 60L + seconds) * 1_000L + millis) * 1_000L
    }

    private fun cleanCueText(line: String): String {
        var text = TAG_STRIP.replace(line, "")
        // Common inline WebVTT/SRT voice and class spans survive tag stripping
        // as plain text; entities are decoded to what the viewer should read.
        text = text.replace("&nbsp;", "\u00A0")
        text = text.replace("&amp;", "&")
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&quot;", "\"")
        return text.trim()
    }
}
