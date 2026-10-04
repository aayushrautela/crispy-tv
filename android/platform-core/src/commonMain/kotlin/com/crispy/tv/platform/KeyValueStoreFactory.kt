package com.crispy.tv.platform

/**
 * Hands out one [KeyValueStore] per *name*, so a caller that wants durable storage asks for a
 * store rather than for a file or a preference.
 *
 * This exists because every such caller used to be handed a `Context` instead, and that is the
 * pin that kept the service graph in `androidMain`: `SharedPreferencesKeyValueStore(appContext,
 * "supabase_sync_lab")` looks like storage and is actually a platform decision, since the name is
 * only meaningful against a platform that knows where to put it. A factory takes that decision
 * once, at the edge, and leaves every caller naming a store and nothing else — which is the same
 * rule as `AppLogger` and `TimeSource`: a platform answers *what a store does*, and the
 * composition root answers *which store, under which name*.
 *
 * A platform implementation must keep **one** backing artefact per name, and it must be stable
 * across processes. The desktop store is one file per name for the same reason its own KDoc gives:
 * a name folded into a file path has to be re-applied on every read and write, and one missed
 * prefix is a silent leak between stores.
 *
 * Deliberately a `fun interface`: there is exactly one operation, and a test that only needs to
 * hand back a map should be able to write one lambda rather than a class.
 */
fun interface KeyValueStoreFactory {
    /**
     * The store called [name]. The same name must always answer with the same storage, so callers
     * may hold on to the result; implementations should therefore cache per name rather than
     * building a fresh handle on every call.
     */
    fun store(name: String): KeyValueStore
}