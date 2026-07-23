package com.crispy.tv.nativeengine.playback

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import java.util.Collections
import java.util.WeakHashMap

private val assHandlersByPlayer = Collections.synchronizedMap(WeakHashMap<ExoPlayer, AssHandler>())

enum class LibassRenderType {
    CUES,
    EFFECTS_CANVAS,
    EFFECTS_OPEN_GL,
    OVERLAY_CANVAS,
    OVERLAY_OPEN_GL;

    companion object {
        fun fromName(name: String?): LibassRenderType =
            entries.firstOrNull { it.name == name } ?: OVERLAY_OPEN_GL
    }
}

internal fun LibassRenderType.toAssRenderType(): AssRenderType = when (this) {
    LibassRenderType.CUES -> AssRenderType.CUES
    LibassRenderType.EFFECTS_CANVAS -> AssRenderType.EFFECTS_CANVAS
    LibassRenderType.EFFECTS_OPEN_GL -> AssRenderType.EFFECTS_OPEN_GL
    LibassRenderType.OVERLAY_CANVAS -> AssRenderType.OVERLAY_CANVAS
    LibassRenderType.OVERLAY_OPEN_GL -> AssRenderType.OVERLAY_OPEN_GL
}

internal val LibassRenderType.usesOverlaySubtitleView: Boolean
    get() = this == LibassRenderType.OVERLAY_CANVAS || this == LibassRenderType.OVERLAY_OPEN_GL

@UnstableApi
fun ExoPlayer.Builder.buildWithAssSupportCompat(
    context: Context,
    renderType: AssRenderType = AssRenderType.CUES,
    dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context),
    extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory(),
    renderersFactory: RenderersFactory = DefaultRenderersFactory(context),
): ExoPlayer {
    val assHandler = AssHandler(renderType)
    val assSubtitleParserFactory = CompatAssSubtitleParserFactory(assHandler)
    val assExtractorsFactory = extractorsFactory.withAssMkvSupportCompat(
        subtitleParserFactory = assSubtitleParserFactory,
        assHandler = assHandler,
    )

    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory)
    mediaSourceFactory.setSubtitleParserFactory(assSubtitleParserFactory)

    val player = this
        .setMediaSourceFactory(mediaSourceFactory)
        .setRenderersFactory(renderersFactory.withAssSupport(assHandler))
        .build()

    assHandlersByPlayer[player] = assHandler
    assHandler.init(player)
    return player
}

fun ExoPlayer.getAssHandlerCompat(): AssHandler? = assHandlersByPlayer[this]

@UnstableApi
private class CompatAssSubtitleParserFactory(
    private val assHandler: AssHandler,
) : SubtitleParser.Factory {
    private val delegate = AssSubtitleParserFactory(assHandler)

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(normalizeSsaFormat(format))

    override fun getCueReplacementBehavior(format: Format): Int =
        delegate.getCueReplacementBehavior(normalizeSsaFormat(format))

    override fun create(format: Format): SubtitleParser = delegate.create(normalizeSsaFormat(format))

    private fun normalizeSsaFormat(format: Format): Format {
        val isSsaByCodecs = format.codecs == MimeTypes.TEXT_SSA
        val isSsaByMime = format.sampleMimeType == MimeTypes.TEXT_SSA
        return if (isSsaByCodecs && !isSsaByMime) {
            format.buildUpon().setSampleMimeType(MimeTypes.TEXT_SSA).build()
        } else {
            format
        }
    }
}

@UnstableApi
private fun ExtractorsFactory.withAssMkvSupportCompat(
    subtitleParserFactory: SubtitleParser.Factory,
    assHandler: AssHandler,
): ExtractorsFactory = ExtractorsFactory {
    val extractors = createExtractors()
    extractors.forEachIndexed { index, extractor ->
        if (extractor is MatroskaExtractor) {
            extractors[index] = AssMatroskaExtractor(subtitleParserFactory, assHandler)
        }
    }
    extractors
}
