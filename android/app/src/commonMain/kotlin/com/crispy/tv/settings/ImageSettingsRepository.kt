package com.crispy.tv.settings

import com.crispy.tv.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_IMAGE_QUALITY = "image_quality"
private const val DEFAULT_IMAGE_QUALITY = "medium"

data class ImageSettings(
    val quality: ImageQuality = ImageQuality.fromKey(DEFAULT_IMAGE_QUALITY),
)

interface ImageSettingsRepository {
    val settings: StateFlow<ImageSettings>
    fun setQuality(quality: ImageQuality)
}

/**
 * [ImageSettingsRepository] over any [KeyValueStore].
 *
 * [onQualityChanged] runs after a quality change is committed, and exists
 * because the one thing a quality change has to do beyond updating the stored
 * value is drop the decoded-image cache -- which is a Coil concern, so it
 * cannot be written here. There is no default: a caller that has no cache to
 * invalidate should say so with `{ }` rather than discover a missing callback
 * at runtime.
 *
 * There is no change listener, for the same reason as
 * [KeyValueStorePlaybackSettingsRepository] and with the same evidence: a
 * second writer on these preferences would be the only thing that could need
 * one, and there is none.
 */
class KeyValueStoreImageSettingsRepository(
    private val store: KeyValueStore,
    private val onQualityChanged: (ImageQuality) -> Unit,
) : ImageSettingsRepository {
    private val _settings = MutableStateFlow(readSettings())
    override val settings: StateFlow<ImageSettings> = _settings.asStateFlow()

    override fun setQuality(quality: ImageQuality) {
        if (_settings.value.quality == quality) {
            return
        }

        _settings.value = _settings.value.copy(quality = quality)
        store.putString(KEY_IMAGE_QUALITY, quality.key)
        onQualityChanged(quality)
    }

    private fun readSettings(): ImageSettings {
        return ImageSettings(
            quality = ImageQuality.fromKey(store.getString(KEY_IMAGE_QUALITY, DEFAULT_IMAGE_QUALITY)),
        )
    }
}
