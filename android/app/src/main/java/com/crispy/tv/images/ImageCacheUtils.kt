package com.crispy.tv.images

import android.content.Context
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader

@OptIn(ExperimentalCoilApi::class)
internal fun clearImageCache(context: Context) {
    val imageLoader = context.imageLoader
    imageLoader.memoryCache?.clear()
    imageLoader.diskCache?.clear()
}
