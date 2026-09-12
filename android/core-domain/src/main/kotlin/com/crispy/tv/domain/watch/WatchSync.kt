package com.crispy.tv.domain.watch

enum class WatchSyncConnection {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

data class WatchSyncState(
    val profileId: String,
    val connection: WatchSyncConnection,
    val isSurfaceVisible: Boolean,
)

/**
 * The surface an invalidation targets, sent by the server on the `watch_changed`
 * SSE event so the client can route each invalidation to the right refetch.
 * [Unknown] is a forward-compat sentinel for kinds a new server may emit that
 * this client does not yet understand — the reducer ignores them.
 */
sealed interface WatchSyncKind {
    data object ContinueWatching : WatchSyncKind
    data object History : WatchSyncKind
    data object Watchlist : WatchSyncKind
    data object Ratings : WatchSyncKind
    data object Home : WatchSyncKind
    data object Unknown : WatchSyncKind

    companion object {
        /** A legacy server omits `kind`; treat it as [ContinueWatching]. */
        fun fromRaw(raw: String?): WatchSyncKind =
            when (raw?.trim()?.lowercase()) {
                "continue_watching" -> ContinueWatching
                "history" -> History
                "watchlist" -> Watchlist
                "ratings" -> Ratings
                "home" -> Home
                null, "" -> ContinueWatching
                else -> Unknown
            }
    }
}

sealed interface WatchSyncEvent {
    data object SurfaceBecameVisible : WatchSyncEvent
    data object SurfaceHidden : WatchSyncEvent
    data object ConnectionOpened : WatchSyncEvent
    data object ConnectionClosed : WatchSyncEvent
    data object MaxDurationElapsed : WatchSyncEvent
    data class InvalidationReceived(
        val profileId: String,
        val kind: WatchSyncKind,
        val atMs: Long,
    ) : WatchSyncEvent
}

sealed interface WatchSyncEffect {
    data object OpenConnection : WatchSyncEffect
    data object CloseConnection : WatchSyncEffect
    data object RefetchContinueWatching : WatchSyncEffect
    data object RefetchHistory : WatchSyncEffect
    data object RefetchWatchlist : WatchSyncEffect
    data object RefetchRatings : WatchSyncEffect
    data object RefetchHome : WatchSyncEffect
}

data class WatchSyncResult(
    val state: WatchSyncState,
    val effects: List<WatchSyncEffect>,
)

fun createWatchSyncState(profileId: String): WatchSyncState =
    WatchSyncState(
        profileId = profileId,
        connection = WatchSyncConnection.DISCONNECTED,
        isSurfaceVisible = false,
    )

/** Maps a server [WatchSyncKind] to the client refetch effects it triggers. */
private fun effectsForKind(kind: WatchSyncKind): List<WatchSyncEffect> =
    when (kind) {
        WatchSyncKind.ContinueWatching -> listOf(WatchSyncEffect.RefetchContinueWatching)
        WatchSyncKind.History ->
            listOf(WatchSyncEffect.RefetchContinueWatching, WatchSyncEffect.RefetchHistory)
        WatchSyncKind.Watchlist -> listOf(WatchSyncEffect.RefetchWatchlist)
        WatchSyncKind.Ratings -> listOf(WatchSyncEffect.RefetchRatings)
        WatchSyncKind.Home -> listOf(WatchSyncEffect.RefetchHome)
        WatchSyncKind.Unknown -> emptyList()
    }

fun reduceWatchSync(state: WatchSyncState, event: WatchSyncEvent): WatchSyncResult {
    return when (event) {
        WatchSyncEvent.SurfaceBecameVisible -> {
            val next = state.copy(isSurfaceVisible = true)
            if (state.connection == WatchSyncConnection.DISCONNECTED) {
                WatchSyncResult(
                    next.copy(connection = WatchSyncConnection.CONNECTING),
                    listOf(WatchSyncEffect.OpenConnection),
                )
            } else {
                WatchSyncResult(next, emptyList())
            }
        }

        WatchSyncEvent.SurfaceHidden -> {
            if (state.connection != WatchSyncConnection.DISCONNECTED) {
                WatchSyncResult(
                    state.copy(isSurfaceVisible = false, connection = WatchSyncConnection.DISCONNECTED),
                    listOf(WatchSyncEffect.CloseConnection),
                )
            } else {
                WatchSyncResult(state.copy(isSurfaceVisible = false), emptyList())
            }
        }

        WatchSyncEvent.ConnectionOpened -> {
            if (state.isSurfaceVisible) {
                WatchSyncResult(
                    state.copy(connection = WatchSyncConnection.CONNECTED),
                    listOf(
                        WatchSyncEffect.RefetchContinueWatching,
                        WatchSyncEffect.RefetchHome,
                    ),
                )
            } else {
                WatchSyncResult(
                    state.copy(connection = WatchSyncConnection.DISCONNECTED),
                    listOf(WatchSyncEffect.CloseConnection),
                )
            }
        }

        WatchSyncEvent.ConnectionClosed -> {
            WatchSyncResult(state.copy(connection = WatchSyncConnection.DISCONNECTED), emptyList())
        }

        is WatchSyncEvent.InvalidationReceived -> {
            if (state.connection == WatchSyncConnection.CONNECTED && event.profileId == state.profileId) {
                WatchSyncResult(state, effectsForKind(event.kind))
            } else {
                WatchSyncResult(state, emptyList())
            }
        }

        WatchSyncEvent.MaxDurationElapsed -> {
            if (state.connection != WatchSyncConnection.DISCONNECTED) {
                val effects = mutableListOf<WatchSyncEffect>(WatchSyncEffect.CloseConnection)
                if (state.isSurfaceVisible) {
                    effects += WatchSyncEffect.OpenConnection
                    WatchSyncResult(state.copy(connection = WatchSyncConnection.CONNECTING), effects)
                } else {
                    WatchSyncResult(state.copy(connection = WatchSyncConnection.DISCONNECTED), effects)
                }
            } else {
                WatchSyncResult(state, emptyList())
            }
        }
    }
}

fun WatchSyncEffect.toContractValue(): String =
    when (this) {
        WatchSyncEffect.OpenConnection -> "open_connection"
        WatchSyncEffect.CloseConnection -> "close_connection"
        WatchSyncEffect.RefetchContinueWatching -> "refetch_continue_watching"
        WatchSyncEffect.RefetchHistory -> "refetch_history"
        WatchSyncEffect.RefetchWatchlist -> "refetch_watchlist"
        WatchSyncEffect.RefetchRatings -> "refetch_ratings"
        WatchSyncEffect.RefetchHome -> "refetch_home"
    }
