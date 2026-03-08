package com.crispy.tv.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val PLAYBACK_SETTINGS_PREFS_NAME = "playback_settings"
internal const val PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED = "skip_intro_enabled"
internal const val PLAYBACK_SETTINGS_KEY_TRAILER_AUTOPLAY_ENABLED = "trailer_autoplay_enabled"
internal const val PLAYBACK_SETTINGS_KEY_TRAILER_MUTED = "trailer_muted"
private const val DEFAULT_SKIP_INTRO_ENABLED = true
private const val DEFAULT_TRAILER_AUTOPLAY_ENABLED = true
private const val DEFAULT_TRAILER_MUTED = false

data class PlaybackSettings(
    val skipIntroEnabled: Boolean = DEFAULT_SKIP_INTRO_ENABLED,
    val trailerAutoplayEnabled: Boolean = DEFAULT_TRAILER_AUTOPLAY_ENABLED,
    val trailerMuted: Boolean = DEFAULT_TRAILER_MUTED,
)

interface PlaybackSettingsRepository {
    val settings: StateFlow<PlaybackSettings>
    fun setSkipIntroEnabled(enabled: Boolean)
    fun setTrailerAutoplayEnabled(enabled: Boolean)
    fun setTrailerMuted(muted: Boolean)
}

private class SharedPreferencesPlaybackSettingsRepository(
    private val preferences: SharedPreferences
) : PlaybackSettingsRepository {
    private val _settings = MutableStateFlow(readSettings(preferences))
    override val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in OBSERVED_KEYS) {
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

    override fun setTrailerAutoplayEnabled(enabled: Boolean) {
        if (_settings.value.trailerAutoplayEnabled == enabled) {
            return
        }

        _settings.value = _settings.value.copy(trailerAutoplayEnabled = enabled)
        preferences.edit().putBoolean(PLAYBACK_SETTINGS_KEY_TRAILER_AUTOPLAY_ENABLED, enabled).apply()
    }

    override fun setTrailerMuted(muted: Boolean) {
        if (_settings.value.trailerMuted == muted) {
            return
        }

        _settings.value = _settings.value.copy(trailerMuted = muted)
        preferences.edit().putBoolean(PLAYBACK_SETTINGS_KEY_TRAILER_MUTED, muted).apply()
    }

    companion object {
        private val OBSERVED_KEYS =
            setOf(
                PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED,
                PLAYBACK_SETTINGS_KEY_TRAILER_AUTOPLAY_ENABLED,
                PLAYBACK_SETTINGS_KEY_TRAILER_MUTED,
            )

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
                    ),
                trailerAutoplayEnabled =
                    preferences.getBoolean(
                        PLAYBACK_SETTINGS_KEY_TRAILER_AUTOPLAY_ENABLED,
                        DEFAULT_TRAILER_AUTOPLAY_ENABLED
                    ),
                trailerMuted =
                    preferences.getBoolean(
                        PLAYBACK_SETTINGS_KEY_TRAILER_MUTED,
                        DEFAULT_TRAILER_MUTED
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
