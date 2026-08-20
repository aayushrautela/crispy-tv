package com.crispy.tv.nativeengine.playback

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource

/**
 * Composes the playback data-source chain: base HTTP factory, optional
 * per-subtitle header injection, [androidx.media3.datasource.DefaultDataSource]
 * wrapping, and optional response-header overriding.
 */
internal object PlatformPlaybackDataSourceFactory {
    fun create(
        context: Context,
        baseHttpDataSourceFactory: HttpDataSource.Factory,
        defaultResponseHeaders: Map<String, String>,
        externalSubtitles: List<PlaybackExternalSubtitle> = emptyList(),
    ): DataSource.Factory {
        val subtitleHeaderFactory = SubtitleRequestHeaderDataSourceFactory(
            upstreamFactory = baseHttpDataSourceFactory,
            externalSubtitles = externalSubtitles,
        )
        val baseFactory: DataSource.Factory = DefaultDataSource.Factory(context, subtitleHeaderFactory)
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
