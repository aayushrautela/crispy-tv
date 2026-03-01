package com.crispy.tv.details.trailer

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TrailerPlaybackSource(
    val videoUrl: String,
    val audioUrl: String? = null,
)

object YouTubeTrailerExtractor {
    private val cache = ConcurrentHashMap<String, TrailerPlaybackSource>()
    private val initLock = Any()

    @Volatile
    private var initialized = false

    fun resolve(videoId: String): TrailerPlaybackSource? {
        val key = videoId.trim()
        if (key.isBlank()) return null
        cache[key]?.let { return it }

        ensureInitialized()

        val url = "https://www.youtube.com/watch?v=$key"
        return runCatching {
            val info = StreamInfo.getInfo(url)
            pickBestSource(info)
        }.getOrNull()?.also { cache[key] = it }
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            NewPipe.init(OkHttpNewPipeDownloader, Localization.fromLocale(Locale.US))
            initialized = true
        }
    }

    private fun pickBestSource(info: StreamInfo): TrailerPlaybackSource? {
        pickBestMuxed(info.videoStreams)?.let { muxed ->
            return TrailerPlaybackSource(videoUrl = muxed.content)
        }

        val video = pickBestVideoOnly(info.videoOnlyStreams) ?: return null
        val audio = pickBestAudio(info.audioStreams)
        return TrailerPlaybackSource(videoUrl = video.content, audioUrl = audio?.content)
    }

    private fun pickBestMuxed(streams: List<VideoStream>): VideoStream? {
        return streams
            .asSequence()
            .filter { it.isUrl }
            .filterNot { it.isVideoOnly }
            .sortedWith(
                compareByDescending<VideoStream> { videoHeight(it) }
                    .thenByDescending { it.fps }
                    .thenByDescending { it.bitrate }
                    .thenBy { videoFormatRank(it.format) }
            )
            .firstOrNull()
    }

    private fun pickBestVideoOnly(streams: List<VideoStream>): VideoStream? {
        return streams
            .asSequence()
            .filter { it.isUrl }
            .filter { it.isVideoOnly }
            .sortedWith(
                compareByDescending<VideoStream> { videoHeight(it) }
                    .thenByDescending { it.fps }
                    .thenByDescending { it.bitrate }
                    .thenBy { videoFormatRank(it.format) }
            )
            .firstOrNull()
    }

    private fun pickBestAudio(streams: List<AudioStream>): AudioStream? {
        return streams
            .asSequence()
            .filter { it.isUrl }
            .sortedWith(
                compareByDescending<AudioStream> { audioBitrate(it) }
                    .thenBy { audioFormatRank(it.format) }
            )
            .firstOrNull()
    }

    private fun videoHeight(stream: VideoStream): Int {
        val h = stream.height
        if (h > 0) return h
        return parseHeightFromResolution(stream.resolution)
    }

    private fun parseHeightFromResolution(resolution: String?): Int {
        if (resolution.isNullOrBlank()) return 0
        val match = HEIGHT_REGEX.find(resolution) ?: return 0
        return match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun videoFormatRank(format: MediaFormat?): Int {
        return when (format) {
            MediaFormat.MPEG_4 -> 0
            MediaFormat.WEBM -> 1
            MediaFormat.v3GPP -> 2
            else -> 3
        }
    }

    private fun audioFormatRank(format: MediaFormat?): Int {
        return when (format) {
            MediaFormat.M4A -> 0
            MediaFormat.OPUS -> 1
            MediaFormat.WEBMA -> 2
            else -> 3
        }
    }

    private fun audioBitrate(stream: AudioStream): Int {
        val avg = stream.averageBitrate
        if (avg > 0) return avg
        val b = stream.bitrate
        if (b > 0) return b
        return 0
    }

    private val HEIGHT_REGEX = Regex("(\\d{2,4})p")

    private object OkHttpNewPipeDownloader : Downloader() {
        private val client =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        @Throws(IOException::class, ReCaptchaException::class)
        override fun execute(request: Request): Response {
            val method = request.httpMethod().uppercase(Locale.US)
            val headers = request.headers()
            val contentType =
                headers.entries
                    .firstOrNull { (k, _) -> k.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
                    ?.toMediaTypeOrNull()
            val bodyBytes = request.dataToSend()
            val body = bodyBytes?.toRequestBody(contentType)
            val emptyBody = EMPTY_BODY.toRequestBody(contentType)

            val builder = OkHttpRequest.Builder().url(request.url())
            for ((name, values) in headers) {
                for (value in values) {
                    builder.addHeader(name, value)
                }
            }

            when (method) {
                "GET" -> builder.get()
                "HEAD" -> builder.head()
                "POST" -> builder.post(body ?: emptyBody)
                "PUT" -> builder.put(body ?: emptyBody)
                "DELETE" -> {
                    if (body != null) builder.delete(body) else builder.delete()
                }
                else -> builder.method(method, body)
            }

            client.newCall(builder.build()).execute().use { resp ->
                val latestUrl = resp.request.url.toString()
                if (resp.code == 429) {
                    throw ReCaptchaException("HTTP 429 from YouTube", latestUrl)
                }

                return Response(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    resp.body?.string(),
                    latestUrl,
                )
            }
        }

        private val EMPTY_BODY = ByteArray(0)
    }
}
