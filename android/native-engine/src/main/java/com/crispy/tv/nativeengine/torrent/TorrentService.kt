package com.crispy.tv.nativeengine.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process as AndroidProcess
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.ZipFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class TorrentService : Service() {
    inner class TorrentBinder : Binder() {
        fun getService(): TorrentService = this@TorrentService
    }

    private data class ActiveTorrent(
        val key: String,
        val infoHash: String,
        val link: String,
        val sessionId: String?,
        val startedAtMs: Long
    )

    private val binder = TorrentBinder()
    private val activeTorrents = ConcurrentHashMap<String, ActiveTorrent>()

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleStopRunnable = Runnable {
        if (activeTorrents.isEmpty()) {
            stopSelf()
        }
    }

    @Volatile
    private var process: java.lang.Process? = null

    @Volatile
    private var preferredHost: String = DEFAULT_HOST

    private val serviceLock = Any()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        ensureRuntimeDirectories()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Torrent engine ready"))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        idleHandler.removeCallbacksAndMessages(null)
        runCatching { stopAll(clearStorage = true) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun hasActiveTorrents(): Boolean = activeTorrents.isNotEmpty()

    fun getDownloadDir(): File = downloadsDir

    fun startLink(link: String, sessionId: String? = null): Boolean {
        val trimmed = link.trim()
        require(trimmed.isNotEmpty()) { "Torrent link cannot be blank" }

        val infoHash = extractInfoHashFromLink(trimmed)
            ?: throw IllegalArgumentException("Unable to extract info hash from torrent link")

        synchronized(serviceLock) {
            cancelIdleStop()

            if (!sessionId.isNullOrBlank()) {
                stopAll(onlyForSessionId = sessionId, clearStorage = false)
            }

            ensureServerReady(timeoutMs = 15_000)

            val addResponse = torrentAction(
                action = "add",
                payload = JSONObject().put("link", trimmed)
            )

            if (addResponse.optString("error").isNotBlank()) {
                throw IllegalStateException("TorrServer add failed: ${addResponse.optString("error")}")
            }

            activeTorrents[infoHash] = ActiveTorrent(
                key = infoHash,
                infoHash = infoHash,
                link = trimmed,
                sessionId = sessionId,
                startedAtMs = System.currentTimeMillis()
            )

            updateNotification()
            return true
        }
    }

    fun getLargestFileIndexFromLink(link: String): Int {
        val infoHash = extractInfoHashFromLink(link)
            ?: throw IllegalArgumentException("Unable to extract info hash from link")

        synchronized(serviceLock) {
            ensureServerReady(timeoutMs = 10_000)
            return getLargestFileIndex(infoHash)
        }
    }

    fun getStreamUrlForLink(link: String, fileIdx: Int): String {
        val infoHash = extractInfoHashFromLink(link)
        if (infoHash != null) {
            return getStreamUrl(infoHash, fileIdx)
        }

        val host = preferredHost
        val encodedLink = URLEncoder.encode(link, Charsets.UTF_8.name())
        return "http://$host:$PORT/stream?link=$encodedLink&index=$fileIdx&play=1"
    }

    fun getStreamUrl(infoHash: String, fileIdx: Int): String {
        synchronized(serviceLock) {
            ensureServerReady(timeoutMs = 10_000)
            return "http://$preferredHost:$PORT/play/${infoHash.lowercase(Locale.US)}/$fileIdx"
        }
    }

    fun stopAll(onlyForSessionId: String? = null, clearStorage: Boolean = true) {
        synchronized(serviceLock) {
            val targets = if (onlyForSessionId == null) {
                activeTorrents.values.toList()
            } else {
                activeTorrents.values.filter { it.sessionId == onlyForSessionId }
            }

            if (targets.isEmpty() && onlyForSessionId != null) {
                return
            }

            targets.forEach { torrent ->
                runCatching {
                    torrentAction(
                        action = "drop",
                        payload = JSONObject().put("hash", torrent.infoHash)
                    )
                }
                runCatching {
                    torrentAction(
                        action = "wipe",
                        payload = JSONObject().put("hash", torrent.infoHash)
                    )
                }
                activeTorrents.remove(torrent.key)
            }

            if (onlyForSessionId == null && clearStorage) {
                performStartupCleanup()
            }

            if (activeTorrents.isEmpty()) {
                stopServerProcess()
                scheduleIdleStop()
            }

            updateNotification()
        }
    }

    private fun ensureServerReady(timeoutMs: Long) {
        if (!isProcessRunning()) {
            startServerProcess()
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var lastError: Throwable? = null

        val hostCandidates = listOf(preferredHost, DEFAULT_HOST, "localhost")
            .distinct()

        while (System.nanoTime() < deadline) {
            for (candidate in hostCandidates) {
                val result = runCatching { callEcho(candidate) }
                if (result.isSuccess) {
                    preferredHost = candidate
                    return
                }
                lastError = result.exceptionOrNull()
            }

            Thread.sleep(200)
        }

        throw IllegalStateException(
            "TorrServer did not become ready in ${timeoutMs}ms. Last error=${lastError?.message}",
            lastError
        )
    }

    private fun callEcho(host: String) {
        val url = "http://$host:$PORT/echo"
        val request = Request.Builder().url(url).get().build()
        val call = okHttpClient.newCall(request)
        call.timeout().timeout(1000, TimeUnit.MILLISECONDS)

        call.execute().use { response ->
            // Consume body to avoid leaking connections.
            response.body?.close()
            if (!response.isSuccessful) {
                throw IOException("Echo endpoint failed with status ${response.code}")
            }
        }
    }

    private fun getLargestFileIndex(infoHash: String): Int {
        val normalizedHash = infoHash.lowercase(Locale.US)

        repeat(20) {
            val response = torrentAction(
                action = "get",
                payload = JSONObject().put("hash", normalizedHash)
            )

            val fileStats = fileStatsFrom(response)
            if (fileStats != null && fileStats.length() > 0) {
                return pickLargestPlayableIndex(fileStats)
            }

            Thread.sleep(150)
        }

        return 0
    }

    private fun fileStatsFrom(obj: JSONObject): JSONArray? {
        if (obj.has("file_stats") && obj.opt("file_stats") is JSONArray) {
            return obj.optJSONArray("file_stats")
        }
        if (obj.has("files") && obj.opt("files") is JSONArray) {
            return obj.optJSONArray("files")
        }

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            if (value is JSONObject) {
                val nested = fileStatsFrom(value)
                if (nested != null) {
                    return nested
                }
            }
        }

        return null
    }

    private fun pickLargestPlayableIndex(array: JSONArray): Int {
        var preferredIndex = -1
        var preferredSize = -1L
        var fallbackIndex = 0
        var fallbackSize = -1L

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val candidateId = item.optInt("id", i)
            if (candidateId < 0) {
                continue
            }

            val path = item.optString("path", item.optString("name", ""))
            val size = item.optLong("length", item.optLong("size", -1L))

            if (size > fallbackSize) {
                fallbackSize = size
                fallbackIndex = candidateId
            }

            if (isPreferredVideoPath(path) && size > preferredSize) {
                preferredSize = size
                preferredIndex = candidateId
            }
        }

        return if (preferredIndex >= 0) preferredIndex else fallbackIndex
    }

    private fun isPreferredVideoPath(path: String): Boolean {
        val lower = path.lowercase(Locale.US)
        return VIDEO_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun torrentAction(action: String, payload: JSONObject): JSONObject {
        val body = payload.put("action", action)
        return postJson("/torrents", body)
    }

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val url = "http://$preferredHost:$PORT$path"
        val request =
            Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .build()

        val call = okHttpClient.newCall(request)
        call.timeout().timeout(20, TimeUnit.SECONDS)

        call.execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $path: $responseText")
            }
            if (responseText.isBlank()) {
                return JSONObject()
            }
            return runCatching { JSONObject(responseText) }
                .getOrElse { JSONObject().put("raw", responseText) }
        }
    }

    private fun ensureRuntimeDirectories() {
        runtimeRoot.mkdirs()
        downloadsDir.mkdirs()
        torrentsDir.mkdirs()
    }

    private fun performStartupCleanup() {
        if (runtimeRoot.exists()) {
            runtimeRoot.deleteRecursively()
        }
        ensureRuntimeDirectories()
    }

    private fun startServerProcess() {
        ensureRuntimeDirectories()

        val binary = resolveBundledTorrServerBinary()
        check(binary.exists()) {
            "Missing TorrServer binary after fallback lookup. sourceDir=${applicationInfo.sourceDir}, nativeLibraryDir=${applicationInfo.nativeLibraryDir}, supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}"
        }

        if (!binary.canExecute()) {
            binary.setExecutable(true)
        }

        val command = listOf(
            binary.absolutePath,
            "--ip",
            DEFAULT_HOST,
            "--port",
            PORT.toString(),
            "--path",
            downloadsDir.absolutePath,
            "--torrentsdir",
            torrentsDir.absolutePath
        )

        val processBuilder = ProcessBuilder(command)
            .directory(runtimeRoot)
            .redirectErrorStream(true)

        process = processBuilder.start().also { startedProcess ->
            Log.i(TAG, "TorrServer process started with binary=${binary.absolutePath}")
            Thread {
                AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_BACKGROUND)
                runCatching {
                    BufferedReader(InputStreamReader(startedProcess.inputStream)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            Log.d(TAG, "[TorrServer] $line")
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun resolveBundledTorrServerBinary(): File {
        val nativeLibDir = applicationInfo.nativeLibraryDir
        if (!nativeLibDir.isNullOrBlank()) {
            val fromNativeLibDir = File(nativeLibDir, TORRSERVER_BINARY_NAME)
            if (fromNativeLibDir.exists() && fromNativeLibDir.isFile) {
                return fromNativeLibDir
            }
        }

        val extracted = extractBinaryFromInstalledApk()
        if (extracted != null && extracted.exists()) {
            return extracted
        }

        throw IllegalStateException(
            "Unable to locate $TORRSERVER_BINARY_NAME in nativeLibraryDir=$nativeLibDir or installed APK libs"
        )
    }

    private fun extractBinaryFromInstalledApk(): File? {
        val sourceApk = applicationInfo.sourceDir ?: return null
        val target = File(runtimeRoot, TORRSERVER_BINARY_NAME)

        if (target.exists() && target.isFile) {
            return target
        }

        return runCatching {
            val extracted = ZipFile(sourceApk).use { zip ->
                val entryName = Build.SUPPORTED_ABIS
                    .asSequence()
                    .map { "lib/$it/$TORRSERVER_BINARY_NAME" }
                    .firstOrNull { zip.getEntry(it) != null }
                    ?: return@use false

                val entry = zip.getEntry(entryName) ?: return@use false
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }

            if (!extracted) {
                return@runCatching null
            }

            target.setExecutable(true)
            target
        }.onFailure { error ->
            Log.e(TAG, "Failed extracting TorrServer binary from source APK", error)
        }.getOrNull()
    }

    private fun stopServerProcess() {
        process?.let { runningProcess ->
            runCatching {
                runningProcess.destroy()
                if (!runningProcess.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    runningProcess.destroyForcibly()
                }
            }
        }
        process = null
    }

    private fun isProcessRunning(): Boolean {
        return process?.isAlive == true
    }

    private fun scheduleIdleStop() {
        idleHandler.removeCallbacks(idleStopRunnable)
        idleHandler.postDelayed(idleStopRunnable, IDLE_TIMEOUT_MS)
    }

    private fun cancelIdleStop() {
        idleHandler.removeCallbacks(idleStopRunnable)
    }

    private fun updateNotification() {
        val notificationManager = getSystemService<NotificationManager>() ?: return
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification("Active torrents: ${activeTorrents.size}")
        )
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Crispy Rewrite Torrent Engine")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Torrent Engine",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Foreground service for torrent session streaming"
        manager.createNotificationChannel(channel)
    }

    private val runtimeRoot: File by lazy { File(filesDir, "torrent-engine") }
    private val downloadsDir: File by lazy { File(runtimeRoot, "data") }
    private val torrentsDir: File by lazy { File(runtimeRoot, "torrents") }

    companion object {
        private const val TAG = "CrispyTorrentService"
        private const val CHANNEL_ID = "crispy_rewrite_torrent_service"
        private const val NOTIFICATION_ID = 9911
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val PORT = 8090
        private const val IDLE_TIMEOUT_MS = 3 * 60 * 1000L
        private const val TORRSERVER_BINARY_NAME = "libtorrserver.so"

        private val INFO_HASH_PATTERN = Pattern.compile("(?i)(?:btih:)?([a-f0-9]{40})")
        private val VIDEO_EXTENSIONS = setOf(
            ".mkv", ".mp4", ".avi", ".mov", ".webm", ".m4v", ".ts", ".m2ts"
        )

        fun extractInfoHashFromLink(link: String): String? {
            val trimmed = link.trim()
            val direct = trimmed.lowercase(Locale.US)
            if (direct.matches(Regex("^[a-f0-9]{40}$"))) {
                return direct
            }

            val matcher = INFO_HASH_PATTERN.matcher(trimmed)
            return if (matcher.find()) matcher.group(1)?.lowercase(Locale.US) else null
        }
    }
}
