package com.crispy.tv.playerui

import com.crispy.tv.addons.streams.AddonStream
import kotlin.uuid.Uuid

/**
 * Hands the already-resolved [AddonStream] from the picker surface (Details/Home) to the player
 * without re-resolving the whole provider list. The stream object is not Parcelable, so it is
 * stashed here under a one-shot key that travels in the launch Intent instead.
 *
 * **It was `androidMain` for `java.util.UUID`, which is the second file in this
 * repository to carry that premise and the first to say plainly that the premise
 * was false.** `UserMutationIds.kt` was the first and it says why in full:
 * `kotlin.uuid` is a `kotlin.*` stdlib package, so it is in every stdlib including
 * Kotlin/Native; `Uuid.random()` is **stable** in the resolved Kotlin 2.4.10 (the
 * class carries `kotlin.WasExperimental`, and of the companion's members only
 * `generateV4` still carries `kotlin.uuid.ExperimentalUuidApi`), so it needs no
 * `@OptIn`; and no dependency was added because there is nothing to add. **The two
 * files differ in one way that is worth stating: this one is a second copy of the
 * claim rather than a restatement of the finding**, and a reader who finds this
 * file first should be sent to `UserMutationIds.kt` for the measurement instead of
 * re-deriving it.
 *
 * **Nothing else moved, and that is the shape of this landing.** The other three
 * files a census of `:app`'s `androidMain` reports as having no hard pin are all
 * correctly placed: `AndroidLanguageLabels.kt` is the platform answer that
 * `commonMain`'s `languageLabelForCode` already takes as a slot, `AppDistribution`
 * is behind `:native-engine`, and `HouseholdAddonsCloudSync` is blocked one layer
 * down by `MetadataAddonRegistry` living in `:addons`'s `androidMain`. **A census
 * answer of "one pin" is a claim about the file that was scanned, not about the
 * set the file belongs to.**
 */
internal object PlayerStreamHandoff {
    private data class Entry(val stream: AddonStream, val lookupId: String)

    private val store = mutableMapOf<String, Entry>()

    fun stash(stream: AddonStream, lookupId: String): String {
        val key = Uuid.random().toString()
        store[key] = Entry(stream, lookupId)
        return key
    }

    fun consume(key: String?): Pair<AddonStream, String>? {
        if (key.isNullOrBlank()) return null
        return store.remove(key)?.let { it.stream to it.lookupId }
    }
}
