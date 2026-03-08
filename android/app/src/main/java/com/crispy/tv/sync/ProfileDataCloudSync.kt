package com.crispy.tv.sync

import android.content.Context
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.settings.AiInsightsMode
import com.crispy.tv.settings.AiInsightsModelType
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
    private val activeProfileStore: ActiveProfileStore = ActiveProfileStore(context),
    private val aiInsightsSettingsStore: AiInsightsSettingsStore = AiInsightsSettingsStore(context),
    private val omdbSettingsStore: OmdbSettingsStore = OmdbSettingsStore(context),
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

        val remote = supabase.getProfileData(accessToken, trimmedProfileId)
        shadowStore.write(
            ProfileDataShadowStore.Snapshot(
                profileId = trimmedProfileId,
                settings = remote.settings,
                catalogPrefs = remote.catalogPrefs,
                traktAuth = remote.traktAuth,
                simklAuth = remote.simklAuth,
                updatedAt = remote.updatedAt
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
                    val remote = supabase.getProfileData(accessToken, trimmedProfileId)
                    ProfileDataShadowStore.Snapshot(
                        profileId = trimmedProfileId,
                        settings = remote.settings,
                        catalogPrefs = remote.catalogPrefs,
                        traktAuth = remote.traktAuth,
                        simklAuth = remote.simklAuth,
                        updatedAt = remote.updatedAt
                    ).also { shadowStore.write(it) }
                }

        val nextSettings = buildSettingsForCloud(baseline.settings)

        supabase.upsertProfileData(
            accessToken = accessToken,
            profileId = trimmedProfileId,
            settings = nextSettings,
            catalogPrefs = baseline.catalogPrefs,
            traktAuth = baseline.traktAuth,
            simklAuth = baseline.simklAuth
        )

        shadowStore.write(
            baseline.copy(
                settings = nextSettings
            )
        )
    }

    private fun applyProfileSettingsToLocal(settings: Map<String, String>) {
        applyPlaybackSettings(settings)
        applyAiInsightsSettings(settings)
        applyMetadataSettings(settings)
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

    private fun applyAiInsightsSettings(settings: Map<String, String>) {
        val currentSettings = aiInsightsSettingsStore.loadSettings()
        val nextSettings =
            currentSettings.copy(
                mode =
                    settings[KEY_AI_INSIGHTS_MODE]?.let { AiInsightsMode.fromRaw(it) }
                        ?: currentSettings.mode,
                modelType =
                    settings[KEY_AI_INSIGHTS_MODEL_TYPE]?.let { AiInsightsModelType.fromRaw(it) }
                        ?: currentSettings.modelType,
                customModelName =
                    settings[KEY_AI_INSIGHTS_CUSTOM_MODEL_NAME]?.trim()
                        ?: currentSettings.customModelName,
            )

        if (nextSettings != currentSettings) {
            aiInsightsSettingsStore.saveSettings(nextSettings)
        }

        if (settings.containsKey(KEY_AI_OPENROUTER_KEY)) {
            aiInsightsSettingsStore.saveOpenRouterKey(settings[KEY_AI_OPENROUTER_KEY].orEmpty())
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
        result[KEY_AI_INSIGHTS_MODEL_TYPE] = aiSettings.modelType.raw

        val customModelName = aiSettings.customModelName.trim()
        if (customModelName.isBlank()) {
            result.remove(KEY_AI_INSIGHTS_CUSTOM_MODEL_NAME)
        } else {
            result[KEY_AI_INSIGHTS_CUSTOM_MODEL_NAME] = customModelName
        }

        val openRouterKey = aiInsightsSettingsStore.loadOpenRouterKey().trim()
        if (openRouterKey.isBlank()) {
            result.remove(KEY_AI_OPENROUTER_KEY)
        } else {
            result[KEY_AI_OPENROUTER_KEY] = openRouterKey
        }

        val omdbKey = omdbSettingsStore.loadOmdbKey().trim()
        if (omdbKey.isBlank()) {
            result.remove(KEY_METADATA_OMDB_KEY)
        } else {
            result[KEY_METADATA_OMDB_KEY] = omdbKey
        }

        return result
    }

    private fun applyMetadataSettings(settings: Map<String, String>) {
        if (settings.containsKey(KEY_METADATA_OMDB_KEY)) {
            omdbSettingsStore.saveOmdbKey(settings[KEY_METADATA_OMDB_KEY].orEmpty())
        }
    }

    private companion object {
        private const val KEY_PLAYBACK_SKIP_INTRO_ENABLED = "playback.skip_intro_enabled"
        private const val KEY_PLAYBACK_TRAILER_AUTOPLAY_ENABLED = "playback.trailer_autoplay_enabled"
        private const val KEY_PLAYBACK_TRAILER_MUTED = "playback.trailer_muted"

        private const val KEY_AI_INSIGHTS_MODE = "ai.insights.mode"
        private const val KEY_AI_INSIGHTS_MODEL_TYPE = "ai.insights.model_type"
        private const val KEY_AI_INSIGHTS_CUSTOM_MODEL_NAME = "ai.insights.custom_model_name"

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
