package com.crispy.tv.settings

import com.crispy.tv.nativeengine.playback.NativePlaybackEnginePreference
import com.crispy.tv.nativeengine.playback.PlayerResizeMode
import com.crispy.tv.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// On-disk key names. Private because nothing outside this file needs them: the
// key *values* are still a compatibility contract with every install that has
// already run, so they must not be renamed, but the names themselves are now an
// implementation detail of the store rather than something callers assemble.
private const val KEY_SKIP_INTRO_ENABLED = "skip_intro_enabled"
private const val KEY_TRAILER_AUTOPLAY_ENABLED = "trailer_autoplay_enabled"
private const val KEY_TRAILER_MUTED = "trailer_muted"
private const val KEY_PLAYBACK_SPEED = "playback_speed"
private const val KEY_MUTED = "muted"
private const val KEY_DEFAULT_AUDIO_LANGUAGE = "default_audio_language"
private const val KEY_DEFAULT_SUBTITLE_LANGUAGE = "default_subtitle_language"
private const val KEY_USE_LIBASS = "use_libass"
private const val KEY_LIBASS_RENDER_TYPE = "libass_render_type"
private const val KEY_RESIZE_MODE = "resize_mode"
private const val KEY_AUTO_SELECT_STREAM = "auto_select_stream"
private const val KEY_PLAYBACK_ENGINE = "playback_engine"
private const val DEFAULT_SKIP_INTRO_ENABLED = true
private const val DEFAULT_TRAILER_AUTOPLAY_ENABLED = true
private const val DEFAULT_TRAILER_MUTED = false
private const val DEFAULT_PLAYBACK_SPEED = 1f
private const val DEFAULT_MUTED = false
private const val DEFAULT_USE_LIBASS = false
private const val DEFAULT_LIBASS_RENDER_TYPE = "OVERLAY_OPEN_GL"
private const val DEFAULT_RESIZE_MODE = "Fit"
private const val DEFAULT_AUTO_SELECT_STREAM = false
private const val DEFAULT_PLAYBACK_ENGINE = "Auto"

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
    val autoSelectStream: Boolean = DEFAULT_AUTO_SELECT_STREAM,
    val playbackEnginePreference: NativePlaybackEnginePreference = NativePlaybackEnginePreference.Auto,
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
    fun setAutoSelectStream(enabled: Boolean)
    fun setResizeMode(mode: PlayerResizeMode)
    fun setPlaybackEnginePreference(preference: NativePlaybackEnginePreference)
}

/**
 * [PlaybackSettingsRepository] over any [KeyValueStore], so the read, normalise
 * and write rules are the same on every platform.
 *
 * ## Why there is no change listener
 *
 * This used to register a `SharedPreferences.OnSharedPreferenceChangeListener`
 * and re-read the whole snapshot whenever any of these keys changed, which only
 * helps if some *other* code holds a second handle on the same preferences file
 * and writes through it. Exactly one caller did:
 * `ProfileDataCloudSync.applyPlaybackSettings` opened its own
 * `SharedPreferences` on the same file and wrote three keys directly, and the
 * listener was the only thing that made those writes reach the live
 * `StateFlow`.
 *
 * That is now gone: the cloud sync writes through this repository like every
 * other caller, so there is one writer and the snapshot in memory is
 * authoritative. Keeping the listener would mean keeping a second writer as
 * well, and `KeyValueStore` has no way to express one.
 */
internal class KeyValueStorePlaybackSettingsRepository(
    private val store: KeyValueStore,
) : PlaybackSettingsRepository {
    private val _settings = MutableStateFlow(readSettings())
    override val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()

    override fun setSkipIntroEnabled(enabled: Boolean) {
        if (_settings.value.skipIntroEnabled == enabled) {
            return
        }

        _settings.value = _settings.value.copy(skipIntroEnabled = enabled)
        store.putBoolean(KEY_SKIP_INTRO_ENABLED, enabled)
    }

    override fun setTrailerAutoplayEnabled(enabled: Boolean) {
        if (_settings.value.trailerAutoplayEnabled == enabled) {
            return
        }

        _settings.value = _settings.value.copy(trailerAutoplayEnabled = enabled)
        store.putBoolean(KEY_TRAILER_AUTOPLAY_ENABLED, enabled)
    }

    override fun setTrailerMuted(muted: Boolean) {
        if (_settings.value.trailerMuted == muted) {
            return
        }

        _settings.value = _settings.value.copy(trailerMuted = muted)
        store.putBoolean(KEY_TRAILER_MUTED, muted)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = if (speed.isFinite() && speed > 0f) speed else DEFAULT_PLAYBACK_SPEED
        if (_settings.value.playbackSpeed == safeSpeed) {
            return
        }

        _settings.value = _settings.value.copy(playbackSpeed = safeSpeed)
        store.putFloat(KEY_PLAYBACK_SPEED, safeSpeed)
    }

    override fun setMuted(muted: Boolean) {
        if (_settings.value.muted == muted) {
            return
        }

        _settings.value = _settings.value.copy(muted = muted)
        store.putBoolean(KEY_MUTED, muted)
    }

    override fun setDefaultAudioLanguage(language: String?) {
        val normalized = language?.trim()?.ifBlank { null }
        if (_settings.value.defaultAudioLanguage == normalized) {
            return
        }

        _settings.value = _settings.value.copy(defaultAudioLanguage = normalized)
        if (normalized != null) {
            store.putString(KEY_DEFAULT_AUDIO_LANGUAGE, normalized)
        } else {
            store.remove(KEY_DEFAULT_AUDIO_LANGUAGE)
        }
    }

    override fun setDefaultSubtitleLanguage(language: String?) {
        val normalized = language?.trim()?.ifBlank { null }
        if (_settings.value.defaultSubtitleLanguage == normalized) {
            return
        }

        _settings.value = _settings.value.copy(defaultSubtitleLanguage = normalized)
        if (normalized != null) {
            store.putString(KEY_DEFAULT_SUBTITLE_LANGUAGE, normalized)
        } else {
            store.remove(KEY_DEFAULT_SUBTITLE_LANGUAGE)
        }
    }

    override fun setUseLibass(enabled: Boolean) {
        if (_settings.value.useLibass == enabled) {
            return
        }

        _settings.value = _settings.value.copy(useLibass = enabled)
        store.putBoolean(KEY_USE_LIBASS, enabled)
    }

    override fun setLibassRenderType(renderType: String) {
        val normalized = renderType.trim().ifBlank { DEFAULT_LIBASS_RENDER_TYPE }
        if (_settings.value.libassRenderType == normalized) {
            return
        }

        _settings.value = _settings.value.copy(libassRenderType = normalized)
        store.putString(KEY_LIBASS_RENDER_TYPE, normalized)
    }

    override fun setAutoSelectStream(enabled: Boolean) {
        if (_settings.value.autoSelectStream == enabled) {
            return
        }

        _settings.value = _settings.value.copy(autoSelectStream = enabled)
        store.putBoolean(KEY_AUTO_SELECT_STREAM, enabled)
    }

    override fun setResizeMode(mode: PlayerResizeMode) {
        if (_settings.value.resizeMode == mode) {
            return
        }

        _settings.value = _settings.value.copy(resizeMode = mode)
        store.putString(KEY_RESIZE_MODE, mode.name)
    }

    override fun setPlaybackEnginePreference(preference: NativePlaybackEnginePreference) {
        if (_settings.value.playbackEnginePreference == preference) {
            return
        }

        _settings.value = _settings.value.copy(playbackEnginePreference = preference)
        store.putString(KEY_PLAYBACK_ENGINE, preference.name)
    }

    private fun readSettings(): PlaybackSettings {
        return PlaybackSettings(
            skipIntroEnabled = store.getBoolean(KEY_SKIP_INTRO_ENABLED, DEFAULT_SKIP_INTRO_ENABLED),
            trailerAutoplayEnabled =
                store.getBoolean(KEY_TRAILER_AUTOPLAY_ENABLED, DEFAULT_TRAILER_AUTOPLAY_ENABLED),
            trailerMuted = store.getBoolean(KEY_TRAILER_MUTED, DEFAULT_TRAILER_MUTED),
            playbackSpeed = store.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED),
            muted = store.getBoolean(KEY_MUTED, DEFAULT_MUTED),
            defaultAudioLanguage = store.getString(KEY_DEFAULT_AUDIO_LANGUAGE, null)
                ?.takeIf { it.isNotBlank() },
            defaultSubtitleLanguage = store.getString(KEY_DEFAULT_SUBTITLE_LANGUAGE, null)
                ?.takeIf { it.isNotBlank() },
            useLibass = store.getBoolean(KEY_USE_LIBASS, DEFAULT_USE_LIBASS),
            libassRenderType =
                store.getString(KEY_LIBASS_RENDER_TYPE, DEFAULT_LIBASS_RENDER_TYPE)
                    ?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_LIBASS_RENDER_TYPE,
            resizeMode =
                store.getString(KEY_RESIZE_MODE, DEFAULT_RESIZE_MODE)
                    ?.let { runCatching { PlayerResizeMode.valueOf(it) }.getOrNull() }
                    ?: PlayerResizeMode.Fit,
            autoSelectStream = store.getBoolean(KEY_AUTO_SELECT_STREAM, DEFAULT_AUTO_SELECT_STREAM),
            playbackEnginePreference =
                store.getString(KEY_PLAYBACK_ENGINE, DEFAULT_PLAYBACK_ENGINE)
                    ?.let { runCatching { NativePlaybackEnginePreference.valueOf(it) }.getOrNull() }
                    ?: NativePlaybackEnginePreference.Auto,
        )
    }
}
