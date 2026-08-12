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

sealed interface WatchSyncEvent {
    data object SurfaceBecameVisible : WatchSyncEvent
    data object SurfaceHidden : WatchSyncEvent
    data object ConnectionOpened : WatchSyncEvent
    data object ConnectionClosed : WatchSyncEvent
    data object MaxDurationElapsed : WatchSyncEvent
    data class InvalidationReceived(val profileId: String, val atMs: Long) : WatchSyncEvent
}

sealed interface WatchSyncEffect {
    data object OpenConnection : WatchSyncEffect
    data object CloseConnection : WatchSyncEffect
    data object RefetchContinueWatching : WatchSyncEffect
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
                    listOf(WatchSyncEffect.RefetchContinueWatching),
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
                WatchSyncResult(state, listOf(WatchSyncEffect.RefetchContinueWatching))
            } else {
                WatchSyncResult(state, emptyList())
            }
        }

        WatchSyncEvent.MaxDurationElapsed -> {
            if (state.connection != WatchSyncConnection.DISCONNECTED) {
                val effects = mutableListOf(WatchSyncEffect.CloseConnection)
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
    }
