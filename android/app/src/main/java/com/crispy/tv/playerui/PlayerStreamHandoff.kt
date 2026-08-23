package com.crispy.tv.playerui

import com.crispy.tv.streams.AddonStream
import java.util.UUID

/**
 * Hands the already-resolved [AddonStream] from the picker surface (Details/Home) to the player
 * without re-resolving the whole provider list. The stream object is not Parcelable, so it is
 * stashed here under a one-shot key that travels in the launch Intent instead.
 */
internal object PlayerStreamHandoff {
    private data class Entry(val stream: AddonStream, val lookupId: String)

    private val store = mutableMapOf<String, Entry>()

    fun stash(stream: AddonStream, lookupId: String): String {
        val key = UUID.randomUUID().toString()
        store[key] = Entry(stream, lookupId)
        return key
    }

    fun consume(key: String?): Pair<AddonStream, String>? {
        if (key.isNullOrBlank()) return null
        return store.remove(key)?.let { it.stream to it.lookupId }
    }
}
