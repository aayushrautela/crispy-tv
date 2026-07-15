package com.crispy.tv.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object CardStyle {
    const val CardBaseWidthDp = 146
    const val CardCornerRadiusDp = 12
    const val LandscapeAspectRatio = 16f / 9f

    fun landscapeCardWidth(baseWidthDp: Int = CardBaseWidthDp): Dp =
        (baseWidthDp * 180 / 110).dp
}