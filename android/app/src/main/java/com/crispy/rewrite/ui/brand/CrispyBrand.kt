package com.crispy.rewrite.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.crispy.rewrite.R

@Composable
fun CrispyMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo),
        contentDescription = null,
        modifier = modifier
    )
}

@Composable
fun CrispyWordmark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_wordmark),
        contentDescription = null,
        modifier = modifier
    )
}
