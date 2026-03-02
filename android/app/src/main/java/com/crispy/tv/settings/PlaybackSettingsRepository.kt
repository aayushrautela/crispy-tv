package com.crispy.tv.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val PLAYBACK_SETTINGS_PREFS_NAME = "playback_settings"
internal const val PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED = "skip_intro_enabled"
private const val DEFAULT_SKIP_INTRO_ENABLED = true

data class PlaybackSettings(
    val skipIntroEnabled: Boolean = DEFAULT_SKIP_INTRO_ENABLED
)

interface PlaybackSettingsRepository {
    val settings: StateFlow<PlaybackSettings>
    fun setSkipIntroEnabled(enabled: Boolean)
}

private class SharedPreferencesPlaybackSettingsRepository(
    private val preferences: SharedPreferences
) : PlaybackSettingsRepository {
    private val _settings = MutableStateFlow(readSettings(preferences))
    override val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED) {
                _settings.value = readSettings(preferences)
            }
        }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun setSkipIntroEnabled(enabled: Boolean) {
        if (_settings.value.skipIntroEnabled == enabled) {
            return
        }

        _settings.value = _settings.value.copy(skipIntroEnabled = enabled)
        preferences.edit().putBoolean(PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED, enabled).apply()
    }

    companion object {
        fun create(context: Context): PlaybackSettingsRepository {
            val prefs =
                context.applicationContext.getSharedPreferences(
                    PLAYBACK_SETTINGS_PREFS_NAME,
                    Context.MODE_PRIVATE
                )
            return SharedPreferencesPlaybackSettingsRepository(prefs)
        }

        private fun readSettings(preferences: SharedPreferences): PlaybackSettings {
            return PlaybackSettings(
                skipIntroEnabled =
                    preferences.getBoolean(
                        PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED,
                        DEFAULT_SKIP_INTRO_ENABLED
                    )
            )
        }
    }
}

object PlaybackSettingsRepositoryProvider {
    @Volatile
    private var instance: PlaybackSettingsRepository? = null

    fun get(context: Context): PlaybackSettingsRepository {
        val existing = instance
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val synchronizedExisting = instance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                SharedPreferencesPlaybackSettingsRepository.create(context).also { created ->
                    instance = created
                }
            }
        }
    }
}
