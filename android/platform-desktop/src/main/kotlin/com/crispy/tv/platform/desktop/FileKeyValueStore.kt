package com.crispy.tv.platform.desktop

import com.crispy.tv.platform.KeyValueStore
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * [KeyValueStore] over one file per store.
 *
 * ## Namespacing
 *
 * The `KeyValueStore` contract says two instances for the same backend must not
 * observe each other's keys. That is enforced by giving every instance its own
 * file named after the store's [name], rather than by prefixing keys inside one
 * shared file. A key prefix would have to be re-applied on every read and
 * write, and a single missed prefix is a silent data leak between stores.
 * Separate files cannot leak, because the platform does the separation.
 *
 * This is the same rule `SharedPreferencesKeyValueStore` applies on the Android
 * side, and it is why both take a `name` rather than letting the caller pass a
 * namespace string.
 *
 * ## The name is sanitised
 *
 * The name becomes part of a filename, and a caller choosing a store name should
 * not have to know that. Anything outside `[A-Za-z0-9._-]` becomes `_`, which
 * also means two distinct names can collapse to one file -- so a name that is
 * not already safe should be rejected rather than mangled, and a name that *is*
 * mangled is a caller bug. `sanitizedName()` is public so a caller can see what
 * its name became.
 *
 * ## Reading a file that cannot be parsed
 *
 * [Properties.load] throws on a malformed file. A desktop application's settings
 * file can be truncated by a hard kill, and crashing on launch with a
 * `IllegalArgumentException` naming a preferences file is a worse outcome than
 * falling back to defaults, so an unreadable file is treated as empty.
 *
 * That is a real trade and it is stated rather than hidden: the *contents* of an
 * unreadable file are lost, and the first write afterwards overwrites the file.
 * The alternative -- refusing to start -- trades a recoverable situation for an
 * unrecoverable one. A caller that must not lose data should write to a
 * temporary file and rename it over the original, which is what [flush] does
 * within a single write but cannot do across a crash.
 */
class FileKeyValueStore(
    private val root: File,
    name: String,
) : KeyValueStore {

    private val file: File = File(root, sanitize(name))

    /**
     * The name this store's file actually has, after sanitising.
     *
     * Exposed because sanitising is lossy, and a caller debugging "where did my
     * settings go" has no other way to see the answer.
     */
    val sanitizedName: String = file.name

    private fun load(): Properties = Properties().apply {
        if (!file.isFile) return@apply
        try {
            file.inputStream().use(::load)
        } catch (_: IllegalArgumentException) {
            // Malformed file: start empty rather than refusing to launch. See
            // the class KDoc -- this discards the contents on the next write.
        }
    }

    override fun getString(key: String, defaultValue: String?): String? =
        load().getProperty(key, defaultValue)

    override fun putString(key: String, value: String) {
        mutate { it.setProperty(key, value) }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        load().let { props ->
            props.getProperty(key)?.toBooleanStrictOrNull() ?: defaultValue
        }

    override fun putBoolean(key: String, value: Boolean) {
        mutate { it.setProperty(key, value.toString()) }
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        load().let { props ->
            props.getProperty(key)?.toIntOrNull() ?: defaultValue
        }

    override fun putInt(key: String, value: Int) {
        mutate { it.setProperty(key, value.toString()) }
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        load().let { props ->
            props.getProperty(key)?.toFloatOrNull() ?: defaultValue
        }

    override fun putFloat(key: String, value: Float) {
        mutate { it.setProperty(key, value.toString()) }
    }

    override fun contains(key: String): Boolean = load().containsKey(key)

    /**
     * The stored keys, **sorted**.
     *
     * `Properties` is backed by a `Hashtable`, whose iteration order is
     * unspecified and changes with the keys present. Returning it directly would
     * make [keys] non-deterministic for two stores holding identical data, which
     * is exactly the kind of output this repository's contracts forbid. Sorting
     * costs nothing at these sizes and makes the answer a function of the
     * contents alone.
     */
    override fun keys(): Set<String> = load().stringPropertyNames().sorted().toSet()

    override fun remove(key: String) {
        mutate { it.remove(key) }
    }

    override fun clear() {
        mutate { it.clear() }
    }

    /**
     * Read, apply [change], write back.
     *
     * Every write is read-modify-write because the file is the only state; a
     * store instance holds no cache, so two instances pointed at the same root
     * and name cannot each hold a stale copy of the other's writes.
     */
    private inline fun mutate(change: (Properties) -> Unit) {
        val props = load()
        change(props)
        flush(props)
    }

    /**
     * Write [props] to [file] atomically, best effort.
     *
     * The whole store is rewritten on every single write. That is the naive
     * choice, and it is the right one at this size: a settings store holds tens
     * of keys, the file is a few hundred bytes, and an in-memory cache would
     * have to be invalidated across instances to stay correct.
     *
     * The write goes to a sibling temporary file which is then moved over the
     * original, so a crash mid-write leaves the previous contents intact rather
     * than a truncated file. `Files.move` with `ATOMIC_MOVE` is not used because
     * it throws on filesystems that cannot promise atomicity, and falling back
     * to a non-atomic rename on a filesystem that cannot is better than not
     * being able to save at all.
     */
    private fun flush(props: Properties) {
        root.mkdirs()
        val temp = File(root, "${file.name}.tmp")
        try {
            temp.outputStream().use { props.store(it, null) }
            if (!temp.renameTo(file)) {
                // Some filesystems refuse to rename onto an existing file.
                file.delete()
                if (!temp.renameTo(file)) throw IOException("Could not write ${file.path}")
            }
        } finally {
            temp.delete()
        }
    }

    private companion object {
        /**
         * Characters safe in a filename on every platform this runs on.
         *
         * `\` and `/` are separators on Windows and the latter on POSIX, and the
         * rest of the disallowed set is punctuation Windows rejects. Being
         * conservative here costs nothing: a store name is a label, not a path.
         *
         * `.` is allowed *within* a name -- `settings.v2` is an ordinary label --
         * but a name consisting only of dots is not, because `.` and `..` are
         * path components rather than filenames and `File(root, "..")` resolves
         * to the root's **parent**. That is a traversal out of the store
         * directory, found by a test asserting a name was made safe; without the
         * guard a store named `..` would read and write beside the directory it
         * was given, and beside every other store's.
         */
        private fun sanitize(name: String): String {
            val safe = name.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-') it else '_' }
            val joined = safe.joinToString("")
            return if (joined.isEmpty() || joined.all { it == '.' }) "_$joined" else joined
        }
    }
}
