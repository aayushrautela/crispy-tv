package com.crispy.tv.search

/**
 * The user's recent search queries, in most-recent-first order, with duplicates
 * and blanks excluded.
 *
 * This is a **type-level port**, not a transport abstraction. The implementation
 * in `androidMain` persists the list as a JSON array in `SharedPreferences`,
 * which is why it cannot be in `commonMain`: `org.json` is a class of the Android
 * platform supplied by `android.jar`, not a dependency of this project, so a KMP
 * module does not have it at all. Swapping it for `kotlinx.serialization` would
 * be a change to the parsing boundary rather than plumbing, and it is not to be
 * done as a side effect of moving a file.
 *
 * What the interface buys is the part that travels: `SearchViewModel` takes this
 * type, so the viewmodel is constructible in a `commonTest` on every target. The
 * rules themselves — trim, reject blank, dedupe case-insensitively, cap the
 * length — are pure and are worth a test of their own, which is why the
 * implementation keeps them rather than the interface.
 */
interface SearchHistoryStore {

    /** The stored queries, most recent first. */
    fun load(): List<String>

    /**
     * Puts [query] at the front, dropping any earlier entry equal to it
     * ignoring case, and returns the updated list. A blank or whitespace-only
     * query leaves the stored list untouched and returns it as it was.
     */
    fun record(query: String): List<String>

    /**
     * Drops the entry equal to [query] ignoring case and returns the updated
     * list. A query that is not stored leaves the list untouched.
     */
    fun remove(query: String): List<String>

    /** Empties the stored list and returns it, which is empty. */
    fun clear(): List<String>
}
