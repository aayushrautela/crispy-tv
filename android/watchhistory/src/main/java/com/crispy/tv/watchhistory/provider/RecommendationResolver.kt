package com.crispy.tv.watchhistory.provider

import com.crispy.tv.player.ProviderRecommendationsResult
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.watchhistory.auth.ProviderSessionStore

internal class RecommendationResolver(
    private val traktProvider: WatchHistoryProvider,
    private val simklProvider: WatchHistoryProvider,
    private val sessionStore: ProviderSessionStore,
    private val traktClientId: String,
    private val simklClientId: String,
) {
    suspend fun listProviderRecommendations(limit: Int, source: WatchProvider?): ProviderRecommendationsResult {
        val targetLimit = limit.coerceAtLeast(1)
        if (source == WatchProvider.LOCAL) {
            return ProviderRecommendationsResult(statusMessage = "Local source selected. Recommendations unavailable.")
        }

        return when (source) {
            WatchProvider.TRAKT -> loadTraktRecommendations(limit = targetLimit)
            WatchProvider.SIMKL -> loadSimklRecommendations(limit = targetLimit)
            null -> {
                val traktResult = loadTraktRecommendations(limit = targetLimit)
                if (traktResult.items.isNotEmpty()) {
                    traktResult
                } else {
                    val simklResult = loadSimklRecommendations(limit = targetLimit)
                    if (simklResult.items.isNotEmpty()) {
                        simklResult
                    } else {
                        ProviderRecommendationsResult(
                            statusMessage =
                                when {
                                    traktResult.statusMessage.contains("Connect", ignoreCase = true) &&
                                        simklResult.statusMessage.contains("Connect", ignoreCase = true) -> {
                                        "Connect Trakt or Simkl to load For You recommendations."
                                    }

                                    simklResult.statusMessage.isNotBlank() -> simklResult.statusMessage
                                    else -> traktResult.statusMessage
                                },
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadTraktRecommendations(limit: Int): ProviderRecommendationsResult {
        if (traktClientId.isBlank()) {
            return ProviderRecommendationsResult(statusMessage = "Trakt client ID missing. Set TRAKT_CLIENT_ID in gradle.properties.")
        }
        if (sessionStore.traktAccessToken().isBlank()) {
            return ProviderRecommendationsResult(statusMessage = "Connect Trakt to load For You recommendations.")
        }

        return try {
            val items = traktProvider.listRecommendations(limit)
            ProviderRecommendationsResult(
                statusMessage = if (items.isEmpty()) "No Trakt recommendations available." else "",
                items = items,
            )
        } catch (_: Throwable) {
            ProviderRecommendationsResult(statusMessage = "Trakt recommendations are temporarily unavailable.")
        }
    }

    private suspend fun loadSimklRecommendations(limit: Int): ProviderRecommendationsResult {
        if (simklClientId.isBlank()) {
            return ProviderRecommendationsResult(statusMessage = "Simkl client ID missing. Set SIMKL_CLIENT_ID in gradle.properties.")
        }
        if (sessionStore.simklAccessToken().isBlank()) {
            return ProviderRecommendationsResult(statusMessage = "Connect Simkl to load For You recommendations.")
        }

        return try {
            val items = simklProvider.listRecommendations(limit)
            ProviderRecommendationsResult(
                statusMessage = if (items.isEmpty()) "No Simkl recommendations available." else "",
                items = items,
            )
        } catch (_: Throwable) {
            ProviderRecommendationsResult(statusMessage = "Simkl recommendations are temporarily unavailable.")
        }
    }
}
