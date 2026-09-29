package com.crispy.tv.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.brand_mark
import com.crispy.tv.ui.resources.brand_wordmark
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun CrispyMark(modifier: Modifier = Modifier) {
    CrispyBrandAsset(
        resource = Res.drawable.brand_mark,
        modifier = modifier,
    )
}

@Composable
fun CrispyWordmark(modifier: Modifier = Modifier) {
    CrispyBrandAsset(
        resource = Res.drawable.brand_wordmark,
        modifier = modifier,
    )
}

@Composable
private fun CrispyBrandAsset(
    resource: DrawableResource,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
