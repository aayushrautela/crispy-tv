package com.crispy.tv.search

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import org.json.JSONArray

/**
 * The [SearchHistoryStore] implementation, persisted as a JSON array in
 * `SharedPreferences`.
 *
 * It is in `androidMain` for two independent reasons, and the port exists so
 * neither of them reaches a caller. `org.json` is a class of the Android
 * platform supplied by `android.jar` rather than a dependency of this project,
 * so a KMP `commonMain` does not have it; and `SharedPreferences` is Android
 * only. `java.util.Locale` is a third, used solely by [normalize] for a
 * case-insensitive comparison — the same JVM-only reason, and one that would
 * remain if the JSON went away tomorrow.
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
        val entries = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()

        val seen = mutableSetOf<String>()
        return buildList {
            for (index in 0 until entries.length()) {
                val normalizedQuery = normalize(entries.optString(index)).orEmpty()
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

        val payload = JSONArray()
        queries.forEach(payload::put)
        prefs.edit().putString(KEY_RECENT_SEARCHES, payload.toString()).commit()
    }

    private fun normalize(query: String): String? = query.trim().takeIf(String::isNotEmpty)

    private companion object {
        private const val PREFS_NAME = "search_preferences"
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val MAX_HISTORY_ITEMS = 8
    }
}
