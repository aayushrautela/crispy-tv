package com.crispy.tv.watchhistory.sync

import com.crispy.tv.domain.watch.WatchSyncEffect
import com.crispy.tv.domain.watch.WatchSyncEvent
import com.crispy.tv.domain.watch.WatchSyncState
import com.crispy.tv.domain.watch.createWatchSyncState
import com.crispy.tv.domain.watch.reduceWatchSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException

/**
 * Thin platform adapter for the watch_sync SSE channel. It owns the socket and
 * the max-duration timer, but all policy lives in the pure [reduceWatchSync]
 * reducer: the adapter only translates socket lifecycle and streamed
 * `watch_changed` events into reducer events, then applies the emitted effects.
 *
 * The [httpClient] is injected so this module never depends on the app's
 * [com.crispy.tv.network.AppHttp] singleton.
 */
class WatchSyncSource(
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
    private val accessToken: String,
    private val profileId: String,
    private val onRefetch: () -> Unit,
    private val maxDurationMs: Long = 30L * 60L * 1000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var call: Call? = null
    private var opened = false
    private var maxDurationJob: Job? = null
    private var state: WatchSyncState = createWatchSyncState(profileId)

    fun onSurfaceVisible() = handle(WatchSyncEvent.SurfaceBecameVisible)

    fun onSurfaceHidden() = handle(WatchSyncEvent.SurfaceHidden)

    fun close() {
        opened = false
        maxDurationJob?.cancel()
        call?.cancel()
        call = null
        scope.cancel()
    }

    private fun handle(event: WatchSyncEvent) {
        val result = reduceWatchSync(state, event)
        state = result.state
        for (effect in result.effects) {
            when (effect) {
                WatchSyncEffect.OpenConnection -> openStream()
                WatchSyncEffect.CloseConnection -> closeStream()
                WatchSyncEffect.RefetchContinueWatching -> onRefetch()
            }
        }
    }

    private fun openStream() {
        if (opened) return
        opened = true
        val url = "$baseUrl/v1/profiles/$profileId/watch/stream".toHttpUrl()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
        call = httpClient.newCall(request)
        call?.enqueue(
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    handle(WatchSyncEvent.ConnectionOpened)
                    scheduleMaxDuration()
                    val source = response.body?.source()
                    if (source == null) {
                        handle(WatchSyncEvent.ConnectionClosed)
                        return
                    }
                    try {
                        readLoop(source)
                    } catch (_: IOException) {
                        // connection ended
                    } finally {
                        opened = false
                        maxDurationJob?.cancel()
                        response.body?.close()
                        if (!call.isCanceled()) handle(WatchSyncEvent.ConnectionClosed)
                    }
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    opened = false
                    maxDurationJob?.cancel()
                    handle(WatchSyncEvent.ConnectionClosed)
                }
            },
        )
    }

    private fun scheduleMaxDuration() {
        maxDurationJob?.cancel()
        maxDurationJob =
            scope.launch {
                delay(maxDurationMs)
                handle(WatchSyncEvent.MaxDurationElapsed)
            }
    }

    private fun readLoop(source: BufferedSource) {
        var eventName: String? = null
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (eventName != null) {
                        dispatch(eventName, data.toString().trim())
                    }
                    eventName = null
                    data.clear()
                }
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim()).append('\n')
            }
        }
    }

    private fun dispatch(
        eventName: String,
        data: String,
    ) {
        if (eventName != "watch_changed") return
        val changedProfile =
            runCatching { JSONObject(data).optString("profileId", "") }.getOrNull() ?: return
        handle(WatchSyncEvent.InvalidationReceived(changedProfile, 0L))
    }

    private fun closeStream() {
        opened = false
        maxDurationJob?.cancel()
        call?.cancel()
        call = null
    }
}
