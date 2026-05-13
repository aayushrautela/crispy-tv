package com.crispy.tv.images

import android.content.Context
import coil.imageLoader

internal fun clearImageCache(context: Context) {
    val imageLoader = context.imageLoader
    imageLoader.memoryCache?.clear()
    imageLoader.diskCache?.clear()
}
