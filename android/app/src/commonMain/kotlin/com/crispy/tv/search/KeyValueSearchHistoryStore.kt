package com.crispy.tv.search

import com.crispy.tv.platform.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray

/**
 * The [SearchHistoryStore] implementation, persisted as a JSON array behind a
 * [KeyValueStore], and therefore reachable from `commonMain`.
 *
 * ## What changed when it moved, and what did not
 *
 * It was in `androidMain` for two independent reasons, and its own KDoc named
 * both: "`SharedPreferences` is Android only, and `java.util.Locale` is used
 * solely by [normalize] for a case-insensitive comparison -- the same JVM-only
 * reason, and **one that would remain if the storage went away tomorrow.**"
 *
 * **The `Locale` claim pointed at the wrong function, and that is the half worth
 * recording.** [normalize] is `query.trim().takeIf(String::isNotEmpty)` and never
 * mentioned a `Locale`; the only use in the file was the dedupe key in
 * [readHistory]. A premise about *which* function is still a premise, and it is
 * subject to being wrong in the same way as a premise about whether one exists --
 * and it is the half that would have sent a reader to add a
 * `displayName`-shaped slot to the wrong parameter.
 *
 * The storage half needed no new abstraction either: the replacement was already
 * in this repo, and `ProfileDataShadowStore` crossed the same seam the same way.
 *
 * ## `Locale.ROOT` was deleted, not slotted
 *
 * `lowercase(Locale.ROOT)` became `lowercase()`. That is safe here for two
 * independent reasons, and both are worth having written down. `Locale.ROOT` *is*
 * the locale `String.lowercase()` uses internally, so the two spellings name the
 * same call. And on Kotlin/Native there is no `lowercase(Locale)` overload at all,
 * so the argument form could never have compiled off the JVM.
 *
 * What would **not** be safe is `lowercase(Locale.getDefault())`, and this
 * repository has already paid for finding that out:
 * `sync/HouseholdAddonsCloudSync.kt` documents a real bug where rendering a URL
 * through the *device* locale lowercases `I` to a dotless `ı` on a Turkish
 * device, so the same manifest keys differently and every sync installs and
 * uninstalls the same addon. Its comment also cross-referenced this class as "the
 * one already using `Locale.ROOT` for its own dedupe key"; that reference is
 * corrected in the same commit, because a comment pointing at a `Locale.ROOT`
 * that is no longer there is a claim no reader can go and check.
 *
 * ## `commit()` became a port write
 *
 * Both writes used `SharedPreferences.Editor.commit()`, and
 * `SharedPreferencesKeyValueStore` uses `apply()` for every member. Three
 * differences, none observable here. `commit()` is synchronous and `apply()` is
 * not, so a write could still be in flight if the process died straight after --
 * a search history is not worth a synchronous fsync. `apply()` updates the
 * in-memory map synchronously, so [load] still sees a write that was just made.
 * And `commit()`'s return value was **discarded at both call sites**, so no
 * caller ever learned whether the disk write had succeeded.
 *
 * ## The rename
 *
 * A class and an interface cannot share a fully-qualified name, which is why
 * [CachingStreamResolver] and `DefaultAccountBootstrapRepository` were renamed the
 * same way. This is a second and stronger reason: the old name asserted the
 * storage backend as part of the type's identity, and from the moment this took a
 * [KeyValueStore] it described something the class no longer knows about --
 * desktop's `FileKeyValueStore` answers the same interface and the name would
 * have been a lie there rather than merely stale here.
 */
class KeyValueSearchHistoryStore(private val store: KeyValueStore) : SearchHistoryStore {

    override fun load(): List<String> = readHistory()

    override fun record(query: String): List<String> {
        val normalizedQuery = normalize(query) ?: return readHistory()
        val updatedHistory =
            buildList {
                add(normalizedQuery)
                addAll(
                    readHistory().filterNot {
                        it.equals(normalizedQuery, ignoreCase = true)
                    }
                )
            }.take(MAX_HISTORY_ITEMS)

        persist(updatedHistory)
        return updatedHistory
    }

    override fun remove(query: String): List<String> {
        val normalizedQuery = normalize(query) ?: return readHistory()
        val updatedHistory =
            readHistory().filterNot {
                it.equals(normalizedQuery, ignoreCase = true)
            }

        persist(updatedHistory)
        return updatedHistory
    }

    override fun clear(): List<String> {
        store.remove(KEY_RECENT_SEARCHES)
        return emptyList()
    }

    private fun readHistory(): List<String> {
        val stored = store.getString(KEY_RECENT_SEARCHES) ?: return emptyList()
        // `org.json` threw `JSONException` and `Json` throws
        // `SerializationException`; `runCatching` catches either and nothing in
        // `:app` names that type, so the malformed-value answer is unchanged.
        val entries = runCatching { Json.parseToJsonElement(stored).jsonArray }.getOrNull()
            ?: return emptyList()

        val seen = mutableSetOf<String>()
        return buildList {
            for (index in 0 until entries.size) {
                // **`as? JsonPrimitive` is a decision, not a rewrite.** Two of
                // `org.json`'s answers change here, and both are fixes or are
                // impossible: a stored JSON null rendered as the four
                // characters `"null"` and became a *search entry* called
                // `null`, and it now reads as absent; and a container renders
                // as its JSON text under `optString`, where here it is skipped
                // -- which cannot occur in a list this class itself writes.
                // A whole number reads identically, and a fractional one keeps
                // its literal text (`1.50`, where `optString` printed `1.5`).
                val raw = (entries.getOrNull(index) as? JsonPrimitive)?.contentOrNull ?: ""
                val normalizedQuery = normalize(raw).orEmpty()
                if (normalizedQuery.isEmpty()) {
                    continue
                }

                val dedupeKey = normalizedQuery.lowercase()
                if (!seen.add(dedupeKey)) {
                    continue
                }

                add(normalizedQuery)
                if (size == MAX_HISTORY_ITEMS) {
                    break
                }
            }
        }
    }

    private fun persist(queries: List<String>) {
        if (queries.isEmpty()) {
            store.remove(KEY_RECENT_SEARCHES)
            return
        }

        val payload = buildJsonArray { queries.forEach { add(JsonPrimitive(it)) } }
        store.putString(KEY_RECENT_SEARCHES, payload.toString())
    }

    private fun normalize(query: String): String? = query.trim().takeIf(String::isNotEmpty)

    private companion object {
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val MAX_HISTORY_ITEMS = 8
    }
}
