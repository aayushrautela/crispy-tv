package com.crispy.tv.watchhistory.provider

internal fun syncStatusLabel(synced: Boolean, token: String, clientId: String): String {
    return when {
        synced -> "ok"
        token.isBlank() -> "skip(no-token)"
        clientId.isBlank() -> "skip(no-client-id)"
        else -> "failed"
    }
}
