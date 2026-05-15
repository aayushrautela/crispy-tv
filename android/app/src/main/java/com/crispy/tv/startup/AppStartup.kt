package com.crispy.tv.startup

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

object AppStartup {
    private val ran = AtomicBoolean(false)

    fun run(context: Context) {
        if (ran.getAndSet(true)) {
            return
        }
    }
}
