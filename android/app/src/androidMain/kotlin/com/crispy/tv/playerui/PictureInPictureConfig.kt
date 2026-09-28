package com.crispy.tv.playerui

import android.graphics.Rect
import android.util.Rational

data class PictureInPictureConfig(
    val enabled: Boolean = false,
    val sourceRect: Rect? = null,
    val aspectRatio: Rational? = null,
)
