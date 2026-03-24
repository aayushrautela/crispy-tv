package com.crispy.tv.sync

import android.content.Context
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.settings.AiInsightsMode
import com.crispy.tv.settings.AiInsightsSettings
import com.crispy.tv.settings.AiInsightsSettingsStore
import com.crispy.tv.settings.OmdbSettingsStore
import com.crispy.tv.settings.PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED
import com.crispy.tv.settings.PLAYBACK_SETTINGS_KEY_TRAILER_AUTOPLAY_ENABLED
import com.crispy.tv.settings.PLAYBACK_SETTINGS_KEY_TRAILER_MUTED
import com.crispy.tv.settings.PLAYBACK_SETTINGS_PREFS_NAME

class ProfileDataCloudSync(
    private val context: Context,
    private val supabase: SupabaseAccountClient,
    private val backend: CrispyBackendClient,
    private val activeProfileStore: ActiveProfileStore = ActiveProfileStore(context),
    private val aiInsightsSettingsStore: AiInsightsSettingsStore = AiInsightsSettingsStore(context),
    private val omdbSettingsStore: OmdbSettingsStore = OmdbSettingsStore(context),
    private val shadowStore: ProfileDataShadowStore = ProfileDataShadowStore(context),
) {
    suspend fun pullForActiveAccount(): Result<Unit> {
        val session =
            try {
                supabase.ensureValidSession()
            } catch (t: Throwable) {
                return Result.failure(t)
            }
        if (session == null) return Result.success(Unit)

        return try {
            pullForAccount(session.accessToken)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun pushForActiveAccount(): Result<Unit> {
        val session =
            try {
                supabase.ensureValidSession()
            } catch (t: Throwable) {
                return Result.failure(t)
            }
        if (session == null) return Result.success(Unit)

        return try {
            pushForAccount(session.accessToken)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

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

    private suspend fun pullForAccount(accessToken: String) {
        val remoteSettings = backend.getAccountSettings(accessToken)
        applyAccountSettingsToLocal(remoteSettings)
        backend.getOpenRouterSecret(accessToken)?.let { secret ->
            aiInsightsSettingsStore.saveOpenRouterKey(secret.value)
        }
        backend.getOmdbApiSecret(accessToken)?.let { secret ->
            omdbSettingsStore.saveOmdbKey(secret.value)
        }
    }

    private suspend fun pushForAccount(accessToken: String) {
        backend.patchAccountSettings(
            accessToken = accessToken,
            settings = buildAccountSettingsForCloud(),
        )

        val openRouterKey = aiInsightsSettingsStore.loadOpenRouterKey().trim()
        if (openRouterKey.isBlank()) {
            backend.deleteOpenRouterSecret(accessToken)
        } else {
            backend.putOpenRouterSecret(accessToken, openRouterKey)
        }

        val omdbKey = omdbSettingsStore.loadOmdbKey().trim()
        if (omdbKey.isBlank()) {
            backend.deleteOmdbApiSecret(accessToken)
        } else {
            backend.putOmdbApiSecret(accessToken, omdbKey)
        }
    }

    private fun applyProfileSettingsToLocal(settings: Map<String, String>) {
        applyPlaybackSettings(settings)
    }

    private fun applyAccountSettingsToLocal(settings: CrispyBackendClient.AccountSettings) {
        val mode = settings.settings[KEY_AI_INSIGHTS_MODE]?.let(AiInsightsMode::fromRaw)
        if (mode != null) {
            val current = aiInsightsSettingsStore.loadSettings()
            if (current.mode != mode) {
                aiInsightsSettingsStore.saveSettings(current.copy(mode = mode))
            }
        }
    }

    private fun applyPlaybackSettings(settings: Map<String, String>) {
        val prefs = context.getSharedPreferences(PLAYBACK_SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var hasChanges = false

        parseBooleanSetting(settings[KEY_PLAYBACK_SKIP_INTRO_ENABLED])?.let { enabled ->
            editor.putBoolean(PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED, enabled)
            hasChanges = true
        }
        parseBooleanSetting(settings[KEY_PLAYBACK_TRAILER_AUTOPLAY_ENABLED])?.let { enabled ->
            editor.putBoolean(PLAYBACK_SETTINGS_KEY_TRAILER_AUTOPLAY_ENABLED, enabled)
            hasChanges = true
        }
        parseBooleanSetting(settings[KEY_PLAYBACK_TRAILER_MUTED])?.let { muted ->
            editor.putBoolean(PLAYBACK_SETTINGS_KEY_TRAILER_MUTED, muted)
            hasChanges = true
        }

        if (hasChanges) {
            editor.apply()
        }
    }

    private fun buildSettingsForCloud(base: Map<String, String>): Map<String, String> {
        val result = base.toMutableMap()

        val playbackPrefs = context.getSharedPreferences(PLAYBACK_SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        val skipIntroEnabled = playbackPrefs.getBoolean(PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED, true)
        val trailerAutoplayEnabled = playbackPrefs.getBoolean(PLAYBACK_SETTINGS_KEY_TRAILER_AUTOPLAY_ENABLED, true)
        val trailerMuted = playbackPrefs.getBoolean(PLAYBACK_SETTINGS_KEY_TRAILER_MUTED, false)
        result[KEY_PLAYBACK_SKIP_INTRO_ENABLED] = skipIntroEnabled.toString()
        result[KEY_PLAYBACK_TRAILER_AUTOPLAY_ENABLED] = trailerAutoplayEnabled.toString()
        result[KEY_PLAYBACK_TRAILER_MUTED] = trailerMuted.toString()

        val aiSettings: AiInsightsSettings = aiInsightsSettingsStore.loadSettings()
        result[KEY_AI_INSIGHTS_MODE] = aiSettings.mode.raw
        result.remove("ai.insights.model_type")
        result.remove("ai.insights.custom_model_name")
        result.remove(KEY_AI_OPENROUTER_KEY)
        result.remove(KEY_METADATA_OMDB_KEY)

        return result
    }

    private fun buildAccountSettingsForCloud(): Map<String, String> {
        val aiSettings: AiInsightsSettings = aiInsightsSettingsStore.loadSettings()
        return linkedMapOf(
            KEY_AI_INSIGHTS_MODE to aiSettings.mode.raw,
        )
    }

    private companion object {
        private const val KEY_PLAYBACK_SKIP_INTRO_ENABLED = "playback.skip_intro_enabled"
        private const val KEY_PLAYBACK_TRAILER_AUTOPLAY_ENABLED = "playback.trailer_autoplay_enabled"
        private const val KEY_PLAYBACK_TRAILER_MUTED = "playback.trailer_muted"

        private const val KEY_AI_INSIGHTS_MODE = "ai.insights.mode"
        private const val KEY_AI_OPENROUTER_KEY = "ai.openrouter_key"
        private const val KEY_METADATA_OMDB_KEY = "metadata.omdb_key"
    }
}

private fun parseBooleanSetting(raw: String?): Boolean? {
    return when (raw?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
