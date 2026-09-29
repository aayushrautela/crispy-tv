package com.crispy.tv.settings

import android.content.Context
import com.crispy.tv.images.clearImageCache
import com.crispy.tv.platform.android.SharedPreferencesKeyValueStore

// See `PLAYBACK_SETTINGS_PREFS_NAME` in PlaybackSettingsRepositoryProvider.kt:
// the key names are in commonMain, the file is an Android concern, and the name
// is a data-compatibility contract with every install that has already run.
private const val IMAGE_SETTINGS_PREFS_NAME = "image_settings"

/**
 * Builds the image settings repository and caches it for the process.
 *
 * The Coil cache clear is wired here rather than inside the repository because
 * it is the one effect of a quality change that is not a stored value, and Coil
 * does not exist off Android.
 */
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
                val appContext = context.applicationContext
                KeyValueStoreImageSettingsRepository(
                    store = SharedPreferencesKeyValueStore(appContext, IMAGE_SETTINGS_PREFS_NAME),
                    onQualityChanged = { clearImageCache(appContext) },
                ).also { created ->
                    instance = created
                }
            }
        }
    }
}
