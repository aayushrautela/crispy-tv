package com.crispy.tv.sync

import android.content.Context
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.settings.PlaybackSettingsRepository
import com.crispy.tv.platform.android.SharedPreferencesKeyValueStore

class ProfileDataCloudSync(
    // Not a property: only the default values below need a `Context`, and
    // leaving it out of the field set says so. The two collaborators that
    // actually persist something take a [com.crispy.tv.platform.KeyValueStore]
    // or a repository now, so this class opens no preferences of its own.
    context: Context,
    private val supabase: AccountApi,
    private val backend: CrispyBackendClient,
    private val playbackSettings: PlaybackSettingsRepository,
    private val activeProfileStore: ActiveProfileStore =
        ActiveProfileStore(SharedPreferencesKeyValueStore(context, "supabase_sync_lab")),
    private val shadowStore: ProfileDataShadowStore = ProfileDataShadowStore(context),
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

private fun parseBooleanSetting(raw: String?): Boolean? {
    return when (raw?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
