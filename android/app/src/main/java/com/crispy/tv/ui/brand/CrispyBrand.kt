package com.crispy.tv.ui.brand

import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil.compose.AsyncImage
import com.crispy.tv.R

@Composable
fun CrispyMark(modifier: Modifier = Modifier) {
    CrispyBrandAsset(
        resId = R.raw.logo,
        modifier = modifier,
    )
}

@Composable
fun CrispyWordmark(modifier: Modifier = Modifier) {
    CrispyBrandAsset(
        resId = R.raw.logo_wordmark,
        modifier = modifier,
    )
}

@Composable
private fun CrispyBrandAsset(
    @RawRes resId: Int,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = resId,
        contentDescription = null,
        modifier = modifier,
    )
}
