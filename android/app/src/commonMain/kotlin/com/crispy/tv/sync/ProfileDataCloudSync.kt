package com.crispy.tv.sync

import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.settings.PlaybackSettingsRepository

/**
 * Keeps one profile's backend settings and the device's local playback settings
 * in step, in both directions.
 *
 * ## What keeps this file in `commonMain`
 *
 * Nothing, now. It was `androidMain` for two reasons and both are gone.
 *
 * The `Context` went first and it was never a property: it appeared only in the
 * default values of [activeProfileStore] and [shadowStore], so it was the
 * *composition root* of this class wearing a constructor's clothes. A `Context`
 * used to open two stores is wiring, and wiring belongs in the factory that
 * builds this class -- `AppGraph.createProfileDataCloudSync` now
 * names both stores and their backing files. The same shape was discharged
 * twice before, by `ProfileDataShadowStore` and by `DefaultAccountBootstrapRepository`.
 *
 * The concrete `CrispyBackendClient` went second, and it went for a different
 * reason: **the class is already in `:android:backend`'s `commonMain`**, so the
 * only thing the type cost was the inability to *test* this class, because a
 * `commonTest` has no way to construct a client that needs an HTTP transport.
 * Typing the parameter to [BackendApi] -- the interface the client already
 * implements with its body unchanged -- makes the whole decision surface
 * reachable, and it is the same move as `ProfileDataShadowStore` taking a
 * `KeyValueStore` rather than opening its own preferences.
 *
 * ## What the sync actually decides
 *
 * Every rule below is now covered by `ProfileDataCloudSyncTest` from
 * `commonTest`: a signed-out user is a no-op rather than an error, a blank
 * profile id is refused before either request is made, a push without a local
 * baseline fetches one first and writes it, and an unreadable value in the
 * payload leaves the local setting alone instead of guessing.
 */
class ProfileDataCloudSync(
    private val supabase: AccountApi,
    private val backend: BackendApi,
    private val playbackSettings: PlaybackSettingsRepository,
    private val activeProfileStore: ActiveProfileStore,
    private val shadowStore: ProfileDataShadowStore,
) {
    suspend fun pullForActiveProfile(): Result<Unit> {
        val session =
            try {
                supabase.ensureValidSession()
            } catch (t: Throwable) {
                return Result.failure(t)
            }
        if (session == null) return Result.success(Unit)

        val profileId = activeProfileStore.getActiveProfileId(session.userId) ?: return Result.success(Unit)
        return try {
            pullForProfile(session.accessToken, profileId)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun pushForActiveProfile(): Result<Unit> {
        val session =
            try {
                supabase.ensureValidSession()
            } catch (t: Throwable) {
                return Result.failure(t)
            }
        if (session == null) return Result.success(Unit)

        val profileId = activeProfileStore.getActiveProfileId(session.userId) ?: return Result.success(Unit)
        return try {
            pushForProfile(session.accessToken, profileId)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun pullForProfile(accessToken: String, profileId: String) {
        val trimmedProfileId = profileId.trim()
        if (trimmedProfileId.isBlank()) return

        val remote = backend.getProfileSettings(accessToken, trimmedProfileId)
        shadowStore.write(
            ProfileDataShadowStore.Snapshot(
                profileId = trimmedProfileId,
                settings = remote.settings,
                catalogPrefs = emptyMap(),
                updatedAt = null,
            )
        )

        applyProfileSettingsToLocal(remote.settings)
    }

    private suspend fun pushForProfile(accessToken: String, profileId: String) {
        val trimmedProfileId = profileId.trim()
        if (trimmedProfileId.isBlank()) return

        val baseline =
            shadowStore.read(trimmedProfileId)
                ?: run {
                    val remote = backend.getProfileSettings(accessToken, trimmedProfileId)
                    ProfileDataShadowStore.Snapshot(
                        profileId = trimmedProfileId,
                        settings = remote.settings,
                        catalogPrefs = emptyMap(),
                        updatedAt = null,
                    ).also { shadowStore.write(it) }
                }

        val nextSettings = buildSettingsForCloud(baseline.settings)

        backend.patchProfileSettings(
            accessToken = accessToken,
            profileId = trimmedProfileId,
            settings = nextSettings,
        )

        shadowStore.write(
            baseline.copy(
                settings = nextSettings,
            )
        )
    }

    private fun applyProfileSettingsToLocal(settings: Map<String, String>) {
        applyPlaybackSettings(settings)
    }

    /**
     * Writes the pulled values through the repository rather than through a
     * second `SharedPreferences` handle on the same file.
     *
     * It used to open its own handle and `putBoolean` the three keys directly,
     * which meant the settings repository had to register an
     * `OnSharedPreferenceChangeListener` to notice -- the listener existed only
     * to reconcile two writers of the same three values. Now that the sync is
     * the only other caller, one writer is enough, and the repository can keep
     * its in-memory snapshot authoritative. Every setter is a no-op when the
     * value is unchanged, so an absent setting in the payload leaves the local
     * value alone.
     */
    private fun applyPlaybackSettings(settings: Map<String, String>) {
        parseBooleanSetting(settings[KEY_PLAYBACK_SKIP_INTRO_ENABLED])?.let {
            playbackSettings.setSkipIntroEnabled(it)
        }
        parseBooleanSetting(settings[KEY_PLAYBACK_TRAILER_AUTOPLAY_ENABLED])?.let {
            playbackSettings.setTrailerAutoplayEnabled(it)
        }
        parseBooleanSetting(settings[KEY_PLAYBACK_TRAILER_MUTED])?.let {
            playbackSettings.setTrailerMuted(it)
        }
    }

    private fun buildSettingsForCloud(base: Map<String, String>): Map<String, String> {
        val result = base.toMutableMap()

        val current = playbackSettings.settings.value
        result[KEY_PLAYBACK_SKIP_INTRO_ENABLED] = current.skipIntroEnabled.toString()
        result[KEY_PLAYBACK_TRAILER_AUTOPLAY_ENABLED] = current.trailerAutoplayEnabled.toString()
        result[KEY_PLAYBACK_TRAILER_MUTED] = current.trailerMuted.toString()

        return result
    }

    private companion object {
        private const val KEY_PLAYBACK_SKIP_INTRO_ENABLED = "playback.skip_intro_enabled"
        private const val KEY_PLAYBACK_TRAILER_AUTOPLAY_ENABLED = "playback.trailer_autoplay_enabled"
        private const val KEY_PLAYBACK_TRAILER_MUTED = "playback.trailer_muted"
    }
}

/**
 * Parses the backend's boolean spelling without turning an unknown value into a guess.
 *
 * The backend is stringly typed, so whitespace and case are normalised, but every other
 * spelling stays `null` and therefore leaves the current local setting untouched.
 */
internal fun parseBooleanSetting(raw: String?): Boolean? {
    return when (raw?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
