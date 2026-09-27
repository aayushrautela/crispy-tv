package com.crispy.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Dimensions {
    val PageHorizontalPaddingCompact: Dp = 16.dp
    val PageHorizontalPaddingMedium: Dp = 24.dp
    val PageHorizontalPaddingExpanded: Dp = 32.dp

    val SectionSpacing: Dp = 28.dp
    val PageBottomPadding: Dp = 24.dp
    val PageTopPadding: Dp = 16.dp

    val CardInternalPadding: Dp = 16.dp
    val ListItemPadding: Dp = 16.dp

    val SmallSpacing: Dp = 8.dp
    val ExtraSmallSpacing: Dp = 4.dp

    val WideCardWidth: Dp = 248.dp
    const val WideCardAspectRatio: Float = 16f / 9f

    val SearchBarPillHeight: Dp = 56.dp
    val AvatarSize: Dp = 30.dp
    val IconSize: Dp = 24.dp

    val SectionTitleSkeletonHeight: Dp = 28.dp
    val SectionSubtitleSkeletonHeight: Dp = 16.dp
    val SectionTitleSkeletonWidthFraction: Float = 0.45f
    val SectionSubtitleSkeletonWidthFraction: Float = 0.3f
}

// `LocalConfiguration` is Android-only even under Compose Multiplatform -- it
// lives in AndroidCompositionLocals_androidKt, so it cannot be referenced from
// commonMain. LocalWindowInfo.containerSize is the portable equivalent and
// reports the same width in Dp on every target.
@ReadOnlyComposable
@Composable
fun responsivePageHorizontalPadding(): Dp {
    val widthDp = LocalWindowInfo.current.containerDpSize.width
    return when {
        widthDp >= 1024.dp -> Dimensions.PageHorizontalPaddingExpanded
        widthDp >= 768.dp -> Dimensions.PageHorizontalPaddingMedium
        else -> Dimensions.PageHorizontalPaddingCompact
    }
}
