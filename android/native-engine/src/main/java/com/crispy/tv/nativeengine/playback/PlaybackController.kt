package com.crispy.tv.nativeengine.playback

import android.content.Context
import android.view.SurfaceView

/**
 * The surface half of the playback controller: the view the video is drawn
 * into, and the wiring that binds it to the engine.
 *
 * It lives in `:native-engine` rather than in `:core-domain` because every
 * member names an Android type -- `Context` and `SurfaceView`. The engine
 * half, [PlaybackSessionController], is the portable one and stays in
 * `:core-domain`.
 */
interface PlaybackSurfaceController {
    /** Creates the view the video is drawn into. The engine keeps the surface. */
    fun createMpvSurfaceView(context: Context): SurfaceView

    /** Binds [surfaceView] to the engine, replacing any previously bound surface. */
    fun attachMpvSurface(surfaceView: SurfaceView)
}

interface PlaybackController : PlaybackSessionController, PlaybackSurfaceController
