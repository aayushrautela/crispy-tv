package com.crispy.rewrite.nativeengine.torrent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.io.Closeable
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
        boundService.getStreamUrlForLink(trimmed, fileIdx)
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
