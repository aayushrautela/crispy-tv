package com.crispy.tv.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val CardBaseWidthDp = 146
internal const val CardCornerRadiusDp = 12
internal const val LandscapeAspectRatio = 16f / 9f

internal fun landscapeCardWidth(baseWidthDp: Int = CardBaseWidthDp): Dp =
    (baseWidthDp * 180 / 110).dp
