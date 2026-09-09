package com.crispy.tv.home

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface HomeRefreshEvent {
    data object PlaybackEnded : HomeRefreshEvent
    data object WatchlistChanged : HomeRefreshEvent
}

object HomeRefreshBus {
    private val _events = MutableSharedFlow<HomeRefreshEvent>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<HomeRefreshEvent> = _events.asSharedFlow()

    fun emit(event: HomeRefreshEvent) {
        _events.tryEmit(event)
    }
}
