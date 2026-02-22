package com.crispy.tv.settings

import android.net.Uri
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object ProviderOAuthCallbackBus {
    private val _callbacks =
        MutableSharedFlow<Uri>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val callbacks: SharedFlow<Uri> = _callbacks

    fun publish(uri: Uri) {
        _callbacks.tryEmit(uri)
    }
}
