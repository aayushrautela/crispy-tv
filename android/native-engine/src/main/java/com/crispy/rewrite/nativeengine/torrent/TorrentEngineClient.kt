package com.crispy.rewrite.nativeengine.torrent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TorrentEngineClient(context: Context) : Closeable {
    private companion object {
        const val LOCALHOST_POLL_INTERVAL_MS = 750L
        const val LOCALHOST_POLL_TIMEOUT_MS = 180_000L
    }

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
        val streamUrl = boundService.getStreamUrlForLink(trimmed, fileIdx)
        awaitLocalStreamReady(streamUrl)
        streamUrl
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

    private suspend fun awaitLocalStreamReady(url: String, timeoutMs: Long = LOCALHOST_POLL_TIMEOUT_MS) {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        var lastStatusCode: Int? = null
        var lastError: Throwable? = null

        while (System.currentTimeMillis() < deadlineMs) {
            val probeResult = runCatching { probeLocalStream(url) }
            if (probeResult.isSuccess) {
                val statusCode = probeResult.getOrThrow()
                lastStatusCode = statusCode
                when {
                    statusCode == HttpURLConnection.HTTP_OK ||
                        statusCode == HttpURLConnection.HTTP_PARTIAL -> return

                    statusCode == HttpURLConnection.HTTP_NOT_FOUND -> {
                        throw IllegalStateException("Localhost stream not found: HTTP 404 for url=$url")
                    }

                    statusCode >= 500 && statusCode != HttpURLConnection.HTTP_UNAVAILABLE -> {
                        throw IllegalStateException("Localhost stream probe failed: HTTP $statusCode for url=$url")
                    }
                }
            } else {
                lastError = probeResult.exceptionOrNull()
            }

            delay(LOCALHOST_POLL_INTERVAL_MS)
        }

        throw IllegalStateException(
            "Timed out waiting for local stream in ${timeoutMs}ms for url=$url (lastStatus=${lastStatusCode ?: -1}, lastError=${lastError?.message})",
            lastError
        )
    }

    private fun probeLocalStream(url: String): Int {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 2_000
            setRequestProperty("Range", "bytes=0-1")
        }

        return try {
            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                connection.inputStream?.close()
            } else {
                connection.errorStream?.close()
            }
            statusCode
        } finally {
            connection.disconnect()
        }
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
