package com.crispy.tv.playerui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlayerNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        PlayerMediaSessionManager.handleNotificationAction(intent.action)
    }
}
