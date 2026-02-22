package com.crispy.rewrite.watchhistory.provider

import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.player.WatchProviderAuthState

internal class ProviderRouter(
    private val traktProvider: WatchHistoryProvider,
    private val simklProvider: WatchHistoryProvider,
) {
    fun providerFor(source: WatchProvider?): WatchHistoryProvider? {
        return when (source) {
            WatchProvider.TRAKT -> traktProvider
            WatchProvider.SIMKL -> simklProvider
            else -> null
        }
    }

    fun providersForSync(source: WatchProvider?): List<WatchHistoryProvider> {
        return when (source) {
            WatchProvider.TRAKT -> listOf(traktProvider)
            WatchProvider.SIMKL -> listOf(simklProvider)
            WatchProvider.LOCAL -> emptyList()
            null -> listOf(traktProvider, simklProvider)
        }
    }

    fun providersForFetch(source: WatchProvider?, authState: WatchProviderAuthState): List<WatchHistoryProvider> {
        return when (source) {
            WatchProvider.TRAKT -> listOf(traktProvider)
            WatchProvider.SIMKL -> listOf(simklProvider)
            WatchProvider.LOCAL -> emptyList()
            null -> {
                when {
                    authState.traktAuthenticated && authState.simklAuthenticated -> listOf(traktProvider, simklProvider)
                    authState.traktAuthenticated -> listOf(traktProvider, simklProvider)
                    authState.simklAuthenticated -> listOf(traktProvider, simklProvider)
                    else -> listOf(traktProvider, simklProvider)
                }
            }
        }
    }

    fun trakt(): WatchHistoryProvider {
        return traktProvider
    }

    fun simkl(): WatchHistoryProvider {
        return simklProvider
    }
}
