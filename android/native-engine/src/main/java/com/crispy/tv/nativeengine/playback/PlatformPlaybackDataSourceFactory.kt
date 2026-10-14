package com.crispy.tv.nativeengine.playback

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource

/**
 * Composes the playback data-source chain: base HTTP factory,
 * [androidx.media3.datasource.DefaultDataSource] wrapping, and optional
 * response-header overriding.
 *
 * Per-subtitle header injection used to live here, but nothing passes
 * subtitles anymore: the sidecar downloads bodies itself with per-subtitle
 * headers, and the media item only carries SSA/TTML. The branch was dead --
 * the factory was built once with an empty list -- so it goes instead of
 * gaining a second caller.
 */
internal object PlatformPlaybackDataSourceFactory {
    fun create(
        context: Context,
        baseHttpDataSourceFactory: HttpDataSource.Factory,
        defaultResponseHeaders: Map<String, String>,
    ): DataSource.Factory {
        val baseFactory: DataSource.Factory = DefaultDataSource.Factory(context, baseHttpDataSourceFactory)
        return if (defaultResponseHeaders.isEmpty()) {
            baseFactory
        } else {
            ResponseHeaderOverridingDataSourceFactory(
                upstreamFactory = baseFactory,
                defaultResponseHeaders = defaultResponseHeaders,
            )
        }
    }
}
