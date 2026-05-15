package com.crispy.tv.settings

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.images.clearImageCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val IMAGE_SETTINGS_PREFS_NAME = "image_settings"
internal const val IMAGE_SETTINGS_KEY_QUALITY = "image_quality"
private const val DEFAULT_IMAGE_QUALITY = "medium"

enum class ImageQuality(val displayName: String, val key: String) {
    LOW("Low", "low"),
    MEDIUM("Medium", "medium"),
    HIGH("High", "high");

    companion object {
        fun fromKey(key: String?): ImageQuality {
            return entries.firstOrNull { it.key == key } ?: MEDIUM
        }
    }
}

data class ImageSettings(
    val quality: ImageQuality = ImageQuality.fromKey(DEFAULT_IMAGE_QUALITY),
)

interface ImageSettingsRepository {
    val settings: StateFlow<ImageSettings>
    fun setQuality(quality: ImageQuality)
}

private class SharedPreferencesImageSettingsRepository(
    private val context: Context,
    private val preferences: SharedPreferences
) : ImageSettingsRepository {
    private val _settings = MutableStateFlow(readSettings(preferences))
    override val settings: StateFlow<ImageSettings> = _settings.asStateFlow()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == IMAGE_SETTINGS_KEY_QUALITY) {
                _settings.value = readSettings(preferences)
            }
        }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun setQuality(quality: ImageQuality) {
        if (_settings.value.quality == quality) {
            return
        }

        _settings.value = _settings.value.copy(quality = quality)
        preferences.edit().putString(IMAGE_SETTINGS_KEY_QUALITY, quality.key).apply()
        clearImageCache(context)
    }

    companion object {
        fun create(context: Context): ImageSettingsRepository {
            val prefs =
                context.applicationContext.getSharedPreferences(
                    IMAGE_SETTINGS_PREFS_NAME,
                    Context.MODE_PRIVATE
                )
            return SharedPreferencesImageSettingsRepository(context.applicationContext, prefs)
        }

        private fun readSettings(preferences: SharedPreferences): ImageSettings {
            return ImageSettings(
                quality = ImageQuality.fromKey(
                    preferences.getString(IMAGE_SETTINGS_KEY_QUALITY, DEFAULT_IMAGE_QUALITY)
                )
            )
        }
    }
}

object ImageSettingsRepositoryProvider {
    @Volatile
    private var instance: ImageSettingsRepository? = null

    fun get(context: Context): ImageSettingsRepository {
        val existing = instance
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val synchronizedExisting = instance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                SharedPreferencesImageSettingsRepository.create(context).also { created ->
                    instance = created
                }
            }
        }
    }
}
