package com.crispy.tv.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.crispy.tv.R

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
