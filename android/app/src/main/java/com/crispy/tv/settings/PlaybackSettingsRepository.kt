package com.crispy.tv.settings

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.nativeengine.playback.PlayerResizeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val PLAYBACK_SETTINGS_PREFS_NAME = "playback_settings"
internal const val PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED = "skip_intro_enabled"
internal const val PLAYBACK_SETTINGS_KEY_TRAILER_AUTOPLAY_ENABLED = "trailer_autoplay_enabled"
internal const val PLAYBACK_SETTINGS_KEY_TRAILER_MUTED = "trailer_muted"
internal const val PLAYBACK_SETTINGS_KEY_PLAYBACK_SPEED = "playback_speed"
internal const val PLAYBACK_SETTINGS_KEY_MUTED = "muted"
internal const val PLAYBACK_SETTINGS_KEY_DEFAULT_AUDIO_LANGUAGE = "default_audio_language"
internal const val PLAYBACK_SETTINGS_KEY_DEFAULT_SUBTITLE_LANGUAGE = "default_subtitle_language"
internal const val PLAYBACK_SETTINGS_KEY_USE_LIBASS = "use_libass"
internal const val PLAYBACK_SETTINGS_KEY_LIBASS_RENDER_TYPE = "libass_render_type"
internal const val PLAYBACK_SETTINGS_KEY_RESIZE_MODE = "resize_mode"
private const val DEFAULT_SKIP_INTRO_ENABLED = true
private const val DEFAULT_TRAILER_AUTOPLAY_ENABLED = true
private const val DEFAULT_TRAILER_MUTED = false
private const val DEFAULT_PLAYBACK_SPEED = 1f
private const val DEFAULT_MUTED = false
private const val DEFAULT_USE_LIBASS = false
private const val DEFAULT_LIBASS_RENDER_TYPE = "OVERLAY_OPEN_GL"
private const val DEFAULT_RESIZE_MODE = "Fit"

data class PlaybackSettings(
    val skipIntroEnabled: Boolean = DEFAULT_SKIP_INTRO_ENABLED,
    val trailerAutoplayEnabled: Boolean = DEFAULT_TRAILER_AUTOPLAY_ENABLED,
    val trailerMuted: Boolean = DEFAULT_TRAILER_MUTED,
    val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val muted: Boolean = DEFAULT_MUTED,
    val defaultAudioLanguage: String? = null,
    val defaultSubtitleLanguage: String? = null,
    val useLibass: Boolean = DEFAULT_USE_LIBASS,
    val libassRenderType: String = DEFAULT_LIBASS_RENDER_TYPE,
    val resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
)

interface PlaybackSettingsRepository {
    val settings: StateFlow<PlaybackSettings>
    fun setSkipIntroEnabled(enabled: Boolean)
    fun setTrailerAutoplayEnabled(enabled: Boolean)
    fun setTrailerMuted(muted: Boolean)
    fun setPlaybackSpeed(speed: Float)
    fun setMuted(muted: Boolean)
    fun setDefaultAudioLanguage(language: String?)
    fun setDefaultSubtitleLanguage(language: String?)
    fun setUseLibass(enabled: Boolean)
    fun setLibassRenderType(renderType: String)
    fun setResizeMode(mode: PlayerResizeMode)
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

    override fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = if (speed.isFinite() && speed > 0f) speed else DEFAULT_PLAYBACK_SPEED
        if (_settings.value.playbackSpeed == safeSpeed) {
            return
        }

        _settings.value = _settings.value.copy(playbackSpeed = safeSpeed)
        preferences.edit().putFloat(PLAYBACK_SETTINGS_KEY_PLAYBACK_SPEED, safeSpeed).apply()
    }

    override fun setMuted(muted: Boolean) {
        if (_settings.value.muted == muted) {
            return
        }

        _settings.value = _settings.value.copy(muted = muted)
        preferences.edit().putBoolean(PLAYBACK_SETTINGS_KEY_MUTED, muted).apply()
    }

    override fun setDefaultAudioLanguage(language: String?) {
        val normalized = language?.trim()?.ifBlank { null }
        if (_settings.value.defaultAudioLanguage == normalized) {
            return
        }

        _settings.value = _settings.value.copy(defaultAudioLanguage = normalized)
        if (normalized != null) {
            preferences.edit().putString(PLAYBACK_SETTINGS_KEY_DEFAULT_AUDIO_LANGUAGE, normalized).apply()
        } else {
            preferences.edit().remove(PLAYBACK_SETTINGS_KEY_DEFAULT_AUDIO_LANGUAGE).apply()
        }
    }

    override fun setDefaultSubtitleLanguage(language: String?) {
        val normalized = language?.trim()?.ifBlank { null }
        if (_settings.value.defaultSubtitleLanguage == normalized) {
            return
        }

        _settings.value = _settings.value.copy(defaultSubtitleLanguage = normalized)
        if (normalized != null) {
            preferences.edit().putString(PLAYBACK_SETTINGS_KEY_DEFAULT_SUBTITLE_LANGUAGE, normalized).apply()
        } else {
            preferences.edit().remove(PLAYBACK_SETTINGS_KEY_DEFAULT_SUBTITLE_LANGUAGE).apply()
        }
    }

    override fun setUseLibass(enabled: Boolean) {
        if (_settings.value.useLibass == enabled) {
            return
        }

        _settings.value = _settings.value.copy(useLibass = enabled)
        preferences.edit().putBoolean(PLAYBACK_SETTINGS_KEY_USE_LIBASS, enabled).apply()
    }

    override fun setLibassRenderType(renderType: String) {
        val normalized = renderType.trim().ifBlank { DEFAULT_LIBASS_RENDER_TYPE }
        if (_settings.value.libassRenderType == normalized) {
            return
        }

        _settings.value = _settings.value.copy(libassRenderType = normalized)
        preferences.edit().putString(PLAYBACK_SETTINGS_KEY_LIBASS_RENDER_TYPE, normalized).apply()
    }

    override fun setResizeMode(mode: PlayerResizeMode) {
        if (_settings.value.resizeMode == mode) {
            return
        }

        _settings.value = _settings.value.copy(resizeMode = mode)
        preferences.edit().putString(PLAYBACK_SETTINGS_KEY_RESIZE_MODE, mode.name).apply()
    }

    companion object {
        private val OBSERVED_KEYS =
            setOf(
                PLAYBACK_SETTINGS_KEY_SKIP_INTRO_ENABLED,
                PLAYBACK_SETTINGS_KEY_TRAILER_AUTOPLAY_ENABLED,
                PLAYBACK_SETTINGS_KEY_TRAILER_MUTED,
                PLAYBACK_SETTINGS_KEY_PLAYBACK_SPEED,
                PLAYBACK_SETTINGS_KEY_MUTED,
                PLAYBACK_SETTINGS_KEY_DEFAULT_AUDIO_LANGUAGE,
                PLAYBACK_SETTINGS_KEY_DEFAULT_SUBTITLE_LANGUAGE,
                PLAYBACK_SETTINGS_KEY_USE_LIBASS,
                PLAYBACK_SETTINGS_KEY_LIBASS_RENDER_TYPE,
                PLAYBACK_SETTINGS_KEY_RESIZE_MODE,
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
                    ),
                playbackSpeed =
                    preferences.getFloat(
                        PLAYBACK_SETTINGS_KEY_PLAYBACK_SPEED,
                        DEFAULT_PLAYBACK_SPEED
                    ),
                muted =
                    preferences.getBoolean(
                        PLAYBACK_SETTINGS_KEY_MUTED,
                        DEFAULT_MUTED
                    ),
                defaultAudioLanguage =
                    preferences.getString(PLAYBACK_SETTINGS_KEY_DEFAULT_AUDIO_LANGUAGE, null)
                        ?.takeIf { it.isNotBlank() },
                defaultSubtitleLanguage =
                    preferences.getString(PLAYBACK_SETTINGS_KEY_DEFAULT_SUBTITLE_LANGUAGE, null)
                        ?.takeIf { it.isNotBlank() },
                useLibass =
                    preferences.getBoolean(
                        PLAYBACK_SETTINGS_KEY_USE_LIBASS,
                        DEFAULT_USE_LIBASS,
                    ),
                libassRenderType =
                    preferences.getString(PLAYBACK_SETTINGS_KEY_LIBASS_RENDER_TYPE, DEFAULT_LIBASS_RENDER_TYPE)
                        ?.takeIf { it.isNotBlank() }
                        ?: DEFAULT_LIBASS_RENDER_TYPE,
                resizeMode =
                    preferences.getString(PLAYBACK_SETTINGS_KEY_RESIZE_MODE, DEFAULT_RESIZE_MODE)
                        ?.let { runCatching { PlayerResizeMode.valueOf(it) }.getOrNull() }
                        ?: PlayerResizeMode.Fit,
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
