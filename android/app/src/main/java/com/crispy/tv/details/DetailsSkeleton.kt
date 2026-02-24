package com.crispy.tv.details

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

@Stable
internal object DetailsSkeletonColors {
    // Neutral greys so loading placeholders don't inherit tinted dynamic themes.
    // Details uses a dark scheme; these values are tuned for that background.
    val Base: Color = Color(0xFF2F3236)
    val Elevated: Color = Color(0xFF3A3D42)
}
