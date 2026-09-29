package com.crispy.tv.settings

import android.content.Context
import com.crispy.tv.images.clearImageCache
import com.crispy.tv.platform.android.SharedPreferencesKeyValueStore

// The `SharedPreferences` file name is the Android-side half of the store: the
// key names live in commonMain, the file they are written into is a property of
// the platform, and this name is a data-compatibility contract with every
// install that has already run.
internal const val PLAYBACK_SETTINGS_PREFS_NAME = "playback_settings"

/**
 * Builds the playback settings repository and caches it for the process.
 *
 * Split out of the settings type itself so the read/normalise/write rules can
 * live in `commonMain` and be tested without a device. Everything here is
 * platform wiring: which `Context`, which file, cached because the settings are
 * read synchronously while the first screen composes and there is no useful
 * way to await it there.
 */
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
                KeyValueStorePlaybackSettingsRepository(
                    SharedPreferencesKeyValueStore(context, PLAYBACK_SETTINGS_PREFS_NAME),
                ).also { created ->
                    instance = created
                }
            }
        }
    }
}
