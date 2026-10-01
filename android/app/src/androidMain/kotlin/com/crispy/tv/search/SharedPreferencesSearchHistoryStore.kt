package com.crispy.tv.search

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray

/**
 * The [SearchHistoryStore] implementation, persisted as a JSON array in
 * `SharedPreferences`.
 *
 * It is in `androidMain` for two independent reasons, and the port exists so
 * neither of them reaches a caller: `SharedPreferences` is Android only, and
 * `java.util.Locale` is used solely by [normalize] for a case-insensitive
 * comparison — the same JVM-only reason, and **one that would remain if the
 * storage went away tomorrow.**
 *
 * **It used to name a third, and that one is gone.** The original list was
 * `org.json`, `SharedPreferences` and `Locale`, on the grounds that "`org.json`
 * is a class of the Android platform supplied by `android.jar` rather than a
 * dependency of this project, so a KMP `commonMain` does not have it." The
 * node type is now `kotlinx.serialization.json.JsonElement`, which is a real
 * KMP dependency, so that reason no longer holds — **and keeping it would have
 * been worse than removing it**, because a reader would re-derive the whole
 * file's placement from a claim that had stopped being true. What remains is
 * the part that is still `androidMain` for reasons no node-type change can
 * reach.
 *
 * The class is named for its persistence rather than reusing the port's name
 * because a class and an interface cannot share a fully-qualified name, which
 * is why [CachingStreamResolver] and [AndroidAccountBootstrapRepository] were
 * renamed the same way.
 */
class SharedPreferencesSearchHistoryStore(context: Context) : SearchHistoryStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        prefs.edit().remove(KEY_RECENT_SEARCHES).commit()
        return emptyList()
    }

    private fun readHistory(): List<String> {
        val stored = prefs.getString(KEY_RECENT_SEARCHES, null) ?: return emptyList()
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

                val dedupeKey = normalizedQuery.lowercase(Locale.ROOT)
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
            prefs.edit().remove(KEY_RECENT_SEARCHES).commit()
            return
        }

        val payload = buildJsonArray { queries.forEach { add(JsonPrimitive(it)) } }
        prefs.edit().putString(KEY_RECENT_SEARCHES, payload.toString()).commit()
    }

    private fun normalize(query: String): String? = query.trim().takeIf(String::isNotEmpty)

    private companion object {
        private const val PREFS_NAME = "search_preferences"
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val MAX_HISTORY_ITEMS = 8
    }
}
