package com.crispy.rewrite.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
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
    
    val SearchBarPillHeight: Dp = 56.dp
    val AvatarSize: Dp = 30.dp
    val IconSize: Dp = 24.dp
}

@ReadOnlyComposable
@Composable
fun responsivePageHorizontalPadding(): Dp {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    return when {
        widthDp >= 1024 -> Dimensions.PageHorizontalPaddingExpanded
        widthDp >= 768 -> Dimensions.PageHorizontalPaddingMedium
        else -> Dimensions.PageHorizontalPaddingCompact
    }
}
