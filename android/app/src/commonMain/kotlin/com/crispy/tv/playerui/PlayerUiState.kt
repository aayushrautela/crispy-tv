package com.crispy.tv.playerui

import androidx.compose.runtime.Immutable
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.introskip.IntroSkipInterval
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativeTrack
import com.crispy.tv.nativeengine.playback.NativeVideoLayout
import com.crispy.tv.nativeengine.playback.PlayerResizeMode
import com.crispy.tv.player.PlaybackIdentity

/**
 * What the player surface is currently showing, as a value.
 *
 * ## Why these two live here and not in `PlayerSessionViewModel.kt`
 *
 * They were extracted so that [com.crispy.tv.playerui.PlayerOverlay] could move into
 * `commonMain`. That composable's first parameter is [PlayerUiState] and it reads
 * `activeSurface` seven times, so **it names both of these** -- and `PlayerSessionViewModel`
 * is a 1,315-line `androidMain` class that holds a Media3 `PlayerView` and a `Context`. A file
 * that names a type declared in an `androidMain` sibling is pinned by it whether or not it
 * imports anything, and this pair is what the overlay was actually pinned by.
 *
 * ## Why the package deliberately did not change
 *
 * Both declarations are referenced from `androidMain` files in `com.crispy.tv.playerui` --
 * [PlayerSessionViewModel], and whatever renders the state. Keeping the package means **no
 * consumer needed an import edit**, which is the same mechanism that let the playback value
 * types leave `:android:native-engine`: a coordinate in an import list cannot tell you which
 * module declares a type, so keeping it identical means the question never arises.
 *
 * ## What is asserted about these
 *
 * Nothing here decides anything; every field is either engine state or a request the overlay
 * forwards. The decisions live in `PlayerSessionDecisions.kt` (which is also in `commonMain`),
 * in [com.crispy.tv.introskip] for the skip intervals, and in the view model that assembles
 * this. This file is deliberately the boring part of the player, so that the parts that are not
 * can move.
 */

/** Which player surface is open over the video, or [NONE] when the overlay is showing nothing. */
enum class PlayerSurface {
    NONE,
    INFO,
    EPISODES,
    MORE,
    STREAMS,
    AUDIO,
    SUBTITLES,
}

/**
 * The whole of what the player overlay renders, as one immutable snapshot.
 *
 * Every field after the title is defaulted so a caller can describe a partial state, and every
 * field is a value rather than a reference to a collaborator -- **which is what lets a
 * composable be a pure function of this type**, and what made the derived-state pins
 * (`isPlaying`, `isBuffering`) worth taking on [com.crispy.tv.nativeengine.playback.NativePlaybackSnapshot]
 * rather than recomputing them here.
 */
@Immutable
data class PlayerUiState(
    val title: String,
    val subtitle: String? = null,
    val isMetadataLoaded: Boolean = false,
    val artworkUrl: String? = null,
    val activeEngine: NativePlaybackEngine = NativePlaybackEngine.EXO,
    val isBuffering: Boolean = true,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val stableDurationMs: Long = 0L,
    val statusMessage: String = "Preparing playback...",
    val errorMessage: String? = null,
    val videoLayout: NativeVideoLayout? = null,
    val details: MediaDetails? = null,
    val activeIdentity: PlaybackIdentity? = null,
    val activeSurface: PlayerSurface = PlayerSurface.NONE,
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int? = null,
    val seasonEpisodes: List<MediaVideo> = emptyList(),
    val episodesIsLoading: Boolean = false,
    val episodesStatusMessage: String = "",
    val recommendedItems: List<CatalogItem> = emptyList(),
    val moreIsLoading: Boolean = false,
    val currentPlaybackUrl: String? = null,
    val audioTracks: List<NativeTrack> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val subtitleTracks: List<NativeTrack> = emptyList(),
    val selectedSubtitleTrackId: String? = null,
    val subtitleDelayMs: Int = 0,
    val resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    val addonSubtitles: List<AddonSubtitle> = emptyList(),
    val addonSubtitlesLoading: Boolean = false,
    val addonSubtitlesError: String? = null,
    /**
     * The intro/outro segments the viewer can skip, empty until the intro-skip
     * service answers for the active episode.
     */
    val introSkipIntervals: List<IntroSkipInterval> = emptyList(),
    /**
     * The `skipIntroEnabled` setting as the last fetch actually read it.
     *
     * Carried rather than read from the repository by the overlay because the
     * overlay is a pure function of this state, and because the two are written
     * in the same `_uiState.update` as the intervals themselves -- so the flag on
     * screen cannot describe a different decision from the one the fetch made.
     */
    val skipIntroEnabled: Boolean = false,
)
