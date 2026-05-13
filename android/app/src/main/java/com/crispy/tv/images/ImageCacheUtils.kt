package com.crispy.tv.images

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader

@OptIn(ExperimentalCoilApi::class)
internal fun clearImageCache(context: Context) {
    val imageLoader = context.imageLoader
    imageLoader.memoryCache?.clear()
    imageLoader.diskCache?.clear()
}
