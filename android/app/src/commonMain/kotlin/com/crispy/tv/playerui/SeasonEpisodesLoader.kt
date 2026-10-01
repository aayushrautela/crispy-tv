package com.crispy.tv.playerui

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.addons.mapping.toMediaVideo
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.player.PlaybackIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shown when the episode request could not be completed at all. */
internal const val EPISODES_FAILED_MESSAGE = "Failed to load episodes."

/** Shown when there is no session to make the episode request with. */
internal const val EPISODES_SIGN_IN_MESSAGE = "Sign in to load episodes."

/** Shown when the request succeeded and the season genuinely has no episodes. */
internal const val EPISODES_EMPTY_MESSAGE = "No episodes found."

/**
 * What [SeasonEpisodesLoader] publishes. One type rather than three slots, because
 * the loading flag and the status line are never set independently of each other
 * in the original code -- every write that clears `episodesIsLoading` sets
 * `episodesStatusMessage` in the same `copy`.
 */
sealed interface SeasonEpisodesOutcome {
    /** The request has started. The list is emptied at the same time. */
    data object Loading : SeasonEpisodesOutcome

    /**
     * The request succeeded. [videos] may legitimately be empty, and then [statusMessage]
     * explains that rather than the screen showing an empty rail with no explanation.
     *
     * The message is a field rather than a second publish because the original set the list and
     * the message in the *same* `copy`: publishing twice would give the season guard two chances
     * to reject the second publish, which is a way of publishing half an outcome.
     */
    data class Loaded(
        val videos: List<MediaVideo>,
        val statusMessage: String = "",
    ) : SeasonEpisodesOutcome

    /** The request did not succeed. [message] is user-facing, not diagnostic. */
    data class Failed(val message: String) : SeasonEpisodesOutcome
}

/**
 * Loads the episode list for one season of a series.
 *
 * ## Why this is here and not in the view model
 *
 * The view model is `androidMain` because it holds a `PlaybackController` and an
 * `AudioFocusManager`, both of which live in `:android:native-engine` -- a plain
 * `com.android.library` that publishes no JVM variant, so no `commonMain` file can name
 * those types. This loader touches neither. Every collaborator it does have is already
 * shared: [AccountApi] and [BackendApi] in `:android:backend`'s `commonMain`,
 * [MediaDetails] and [MediaVideo] in `:android:addons`'s `commonMain`, [PlaybackIdentity]
 * in `:android:player`'s `commonMain`. The state it needs from the screen arrives
 * through slots, which is the same shape as [PlaybackProgressReporter].
 *
 * ## The three slots, and why each is a slot
 *
 * - [ioDispatcher] has **no default**, for the reason the metadata loader has: `Dispatchers.IO`
 *   does not exist in `commonMain`, and defaulting this to `Dispatchers.Default` would compile on
 *   every target and silently put blocking HTTP on a CPU-sized pool.
 * - [details] and [identity] are read inside the request, at a moment later than the call. A
 *   snapshot taken at the call would be wrong for exactly the case this class exists to handle:
 *   the user can navigate away or change title while the request is in flight.
 * - [selectedSeason] is the guard's whole purpose, and reading it through a slot is what makes
 *   the stale-response rule testable at all -- see [publish].
 *
 * ## The cache is shared, and that is a measured decision rather than a tidy one
 *
 * [cache] is a constructor parameter, not a field this class owns, because the view model has
 * **five other readers** of that map beyond this fetch: it clears it, and it reads it when
 * choosing which seasons are already available, twice, and when deciding what to show when
 * there is no cached season at all. A map owned here would have been a second cache that
 * disagreed with the first, and the second one would have been the one the screen consulted.
 * (The first draft of this file did own one, and its KDoc claimed the cache "moved with it"
 * because it belonged to the fetch rather than the screen -- measured against those five readers,
 * that claim was simply wrong. A cache is a thing you share.)
 *
 * ## What is deliberately not here
 *
 * The "this is a movie, there are no seasons" early return, which reads `details.itemType` and is
 * therefore still a screen-level fact and stays with the caller.
 */
class SeasonEpisodesLoader(
    private val accountApi: AccountApi,
    private val backendApi: BackendApi,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val cache: MutableMap<Int, List<MediaVideo>>,
    private val details: () -> MediaDetails?,
    private val identity: () -> PlaybackIdentity?,
    private val selectedSeason: () -> Int?,
    private val onOutcome: (SeasonEpisodesOutcome) -> Unit,
) {

    /**
     * Loads [season], publishing through [onOutcome].
     *
     * A cached season publishes and returns **without** touching the network, and without
     * publishing `Loading` first -- the list is already known, so the spinner would be a lie.
     * Otherwise `Loading` is published *synchronously*, before the coroutine is launched,
     * for the same reason the metadata loader publishes its loading flag there: the rail
     * must show a spinner on the same frame the user asked for it.
     */
    fun load(season: Int, force: Boolean = false) {
        val cached = if (force) null else cache[season]
        if (cached != null) {
            onOutcome(SeasonEpisodesOutcome.Loaded(cached))
            return
        }

        onOutcome(SeasonEpisodesOutcome.Loading)
        scope.launch {
            // A thrown session lookup and a null session are **different** answers: the original
            // reported "Failed to load episodes." for the throw and "Sign in to load episodes."
            // for the null, so `runCatching { }.getOrNull()` alone would silently collapse a
            // backend failure into "please sign in".
            val sessionAttempt = runCatching {
                withContext(ioDispatcher) { accountApi.ensureValidSession() }
            }
            if (sessionAttempt.isFailure) {
                return@launch publishFailure(season, EPISODES_FAILED_MESSAGE)
            }
            val session = sessionAttempt.getOrNull()
            if (session == null) {
                return@launch publishFailure(season, EPISODES_SIGN_IN_MESSAGE)
            }

            // Read now, not at the call: the identity can change while the session is fetched,
            // and a request for the previous title's seasons would fill this season's list.
            val seriesItemId = identity()?.seriesItemId?.trim()?.takeIf { it.isNotBlank() }
                ?: details()?.itemId?.trim()?.takeIf { it.isNotBlank() }

            val response = if (seriesItemId == null) {
                null
            } else {
                runCatching {
                    withContext(ioDispatcher) {
                        backendApi.getSeriesEpisodes(
                            accessToken = session.accessToken,
                            seriesItemId = seriesItemId,
                            season = season,
                        )
                    }
                }.getOrNull()
            }

            if (response == null) {
                return@launch publishFailure(season, EPISODES_FAILED_MESSAGE)
            }

            val videos = response.items.mapNotNull { it.toMediaVideo() }
            cache[season] = videos
            publish(season) {
                SeasonEpisodesOutcome.Loaded(
                    videos = videos,
                    statusMessage = if (videos.isEmpty()) EPISODES_EMPTY_MESSAGE else "",
                )
            }
        }
    }

    /**
     * Publishes a failure, unless the user has since selected a different season.
     *
     * This is the one place the "stale response" rule lives. It appeared **five** times in the
     * original -- four carrying a status message, three of them the same one -- and the
     * repetition is why the rule was untestable: there was no single function a test could call. The rule is that a response
     * for season N must not write into the state the screen is showing for season M, because
     * the screen would then show M's episodes under M's heading while holding N's data.
     *
     * A failure also does *not* clear [SeasonEpisodesOutcome.Loading]'s list, because it never
     * had one -- the original wrote the failure over a state whose `seasonEpisodes` was already
     * emptied by the `Loading` publish.
     */
    private fun publishFailure(season: Int, message: String) {
        publish(season) { SeasonEpisodesOutcome.Failed(message) }
    }

    /** Publishes [outcome] only while [season] is still the season on screen. */
    private fun publish(season: Int, outcome: () -> SeasonEpisodesOutcome) {
        if (selectedSeason() != season) return
        onOutcome(outcome())
    }
}