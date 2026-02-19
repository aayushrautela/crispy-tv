package com.crispy.rewrite.nativeengine.torrent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred

class TorrentEngineClient(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val bindMutex = Mutex()

    @Volatile
    private var service: TorrentService? = null

    @Volatile
    private var isBound: Boolean = false

    @Volatile
    private var pendingConnection: CompletableDeferred<TorrentService>? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val resolved = (binder as? TorrentService.TorrentBinder)?.getService()
                ?: return

            service = resolved
            pendingConnection?.complete(resolved)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            pendingConnection?.completeExceptionally(IllegalStateException("Torrent service disconnected"))
            pendingConnection = null
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            isBound = false
            pendingConnection?.completeExceptionally(IllegalStateException("Torrent service binding died"))
            pendingConnection = null
        }
    }

    suspend fun startTorrentAndResolveStreamUrl(
        magnetLink: String,
        sessionId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val trimmed = magnetLink.trim()
        require(trimmed.startsWith("magnet:?")) {
            "Magnet URI must start with magnet:?"
        }

        val boundService = ensureConnected()
        val started = boundService.startLink(trimmed, sessionId)
        check(started) { "Torrent service did not accept start request" }

        val fileIdx = boundService.getLargestFileIndexFromLink(trimmed)
        val url = boundService.getStreamUrlForLink(trimmed, fileIdx)
        awaitProgressiveStreamReady(url, timeoutMs = 45_000)
        url
    }

    private fun awaitProgressiveStreamReady(url: String, timeoutMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var lastError: Throwable? = null

        while (System.nanoTime() < deadline) {
            val result = runCatching {
                probeUrlForMediaSignature(url)
            }

            if (result.isSuccess) {
                val probe = result.getOrNull()
                if (probe != null && probe.isLikelyMedia) {
                    return
                }
            } else {
                lastError = result.exceptionOrNull()
            }

            Thread.sleep(250)
        }

        throw IllegalStateException(
            "Localhost stream not ready in ${timeoutMs}ms for url=$url (lastError=${lastError?.message})",
            lastError
        )
    }

    private data class StreamProbe(
        val responseCode: Int,
        val contentType: String?,
        val bytesRead: Int,
        val firstBytes: ByteArray,
        val isLikelyMedia: Boolean
    )

    private fun probeUrlForMediaSignature(url: String): StreamProbe {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2000
            readTimeout = 2000
            setRequestProperty("Range", "bytes=0-2047")
        }

        try {
            val responseCode = connection.responseCode
            val contentType = connection.contentType
            val stream: InputStream? = when (responseCode) {
                in 200..299 -> connection.inputStream
                else -> connection.errorStream
            }

            val firstBytes = ByteArray(256)
            val bytesRead = stream?.use { it.read(firstBytes) } ?: -1
            val actualBytes = if (bytesRead > 0) firstBytes.copyOf(bytesRead) else ByteArray(0)

            val isLikelyMedia = responseCode in 200..299 && bytesRead > 8 && looksLikeMedia(actualBytes)
            return StreamProbe(
                responseCode = responseCode,
                contentType = contentType,
                bytesRead = bytesRead,
                firstBytes = actualBytes,
                isLikelyMedia = isLikelyMedia
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun looksLikeMedia(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return false
        }

        // HTML/XML/JSON/playlist heuristics
        val firstChar = bytes[0].toInt().toChar()
        if (firstChar == '<' || firstChar == '{' || firstChar == '[' || firstChar == '#') {
            return false
        }

        // MPEG-TS sync byte
        if (bytes[0] == 0x47.toByte()) {
            return true
        }

        // Matroska/WebM EBML header: 1A 45 DF A3
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()
        ) {
            return true
        }

        // AVI: RIFF....AVI
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            return true
        }

        // MP4: ....ftyp
        if (bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) {
            return true
        }

        // MPEG-PS: 00 00 01 BA
        if (bytes.size >= 4 &&
            bytes[0] == 0x00.toByte() &&
            bytes[1] == 0x00.toByte() &&
            bytes[2] == 0x01.toByte() &&
            bytes[3] == 0xBA.toByte()
        ) {
            return true
        }

        return false
    }

    suspend fun stopAll(clearStorage: Boolean = true) {
        withContext(Dispatchers.IO) {
            ensureConnected().stopAll(clearStorage = clearStorage)
        }
    }

    fun stopAllIfConnected(clearStorage: Boolean = true) {
        val localService = service ?: return
        Thread {
            runCatching { localService.stopAll(clearStorage = clearStorage) }
            runCatching { appContext.stopService(Intent(appContext, TorrentService::class.java)) }
        }.start()
    }

    override fun close() {
        if (isBound) {
            runCatching { appContext.unbindService(serviceConnection) }
        }
        isBound = false
        service = null
        pendingConnection = null
    }

    private suspend fun ensureConnected(): TorrentService {
        service?.let { return it }

        return bindMutex.withLock {
            service?.let { return@withLock it }

            val deferred = CompletableDeferred<TorrentService>()
            pendingConnection = deferred

            val intent = Intent(appContext, TorrentService::class.java)
            ContextCompat.startForegroundService(appContext, intent)

            val bound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                pendingConnection = null
                throw IllegalStateException("Failed to bind TorrentService")
            }

            isBound = true
            withTimeout(10_000) { deferred.await() }
        }
    }
}
