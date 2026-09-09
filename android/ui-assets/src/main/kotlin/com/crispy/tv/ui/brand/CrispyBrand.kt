package com.crispy.tv.ui.brand

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.crispy.tv.ui.assets.R

@Composable
fun CrispyMark(modifier: Modifier = Modifier) {
    CrispyBrandAsset(
        resId = R.drawable.brand_mark,
        modifier = modifier,
    )
}

@Composable
fun CrispyWordmark(modifier: Modifier = Modifier) {
    CrispyBrandAsset(
        resId = R.drawable.brand_wordmark,
        modifier = modifier,
    )
}

@Composable
private fun CrispyBrandAsset(
    @DrawableRes resId: Int,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
