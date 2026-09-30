package com.crispy.tv.platform.desktop

import com.crispy.tv.platform.KeyValueStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FileKeyValueStore] against a temporary directory.
 *
 * The store holds no in-memory state, so these cases also pin the property that
 * makes it safe for two instances to share a root: a write through one is visible
 * to the next read through the other. That is the case a caching store would
 * break, and it is the case nothing else in this suite would notice.
 */
class FileKeyValueStoreTest {

    private val root: File = createTempDirectory("crispy-store").toFile()

    private fun store(name: String) = FileKeyValueStore(root, name)

    @Test
    fun `a value written by one instance is read by another`() {
        store("playback").putString("quality", "high")
        assertEquals("high", store("playback").getString("quality", null))
    }

    @Test
    fun `each typed accessor round-trips its own type`() {
        val store = store("typed")
        store.putString("s", "text")
        store.putBoolean("b", true)
        store.putInt("i", -7)
        store.putFloat("f", 1.5f)

        assertEquals("text", store.getString("s", null))
        assertTrue(store.getBoolean("b", false))
        assertEquals(-7, store.getInt("i", 0))
        assertEquals(1.5f, store.getFloat("f", 0f))
    }

    @Test
    fun `a missing key returns the caller's default and not a zero`() {
        val store = store("defaults")
        assertNull(store.getString("absent", null))
        assertEquals("fallback", store.getString("absent", "fallback"))
        assertTrue(store.getBoolean("absent", true))
        assertEquals(42, store.getInt("absent", 42))
        assertEquals(2.5f, store.getFloat("absent", 2.5f))
    }

    /**
     * The namespacing rule, which is the whole reason this store takes a name.
     *
     * Two stores for the same backend must not observe each other's keys. If a
     * future change replaces the per-name file with a shared file plus a key
     * prefix, this is the case that fails -- and it is the only one that does.
     */
    @Test
    fun `two stores with different names cannot see each other's keys`() {
        store("backend-a").putString("token", "a-secret")
        store("backend-b").putString("token", "b-secret")

        assertEquals("a-secret", store("backend-a").getString("token", null))
        assertEquals("b-secret", store("backend-b").getString("token", null))
        assertEquals(setOf("token"), store("backend-a").keys())
    }

    @Test
    fun `two stores with the same name do share keys, because they are one store`() {
        store("shared").putInt("rows", 3)
        assertEquals(3, store("shared").getInt("rows", 0))
    }

    @Test
    fun `a name is sanitised into a single safe filename`() {
        val store = store("auth/../tokens")
        assertFalse(store.sanitizedName.contains('/'), "sanitised name still holds a separator")
        assertFalse(store.sanitizedName.contains('\\'), "sanitised name still holds a separator")
    }

    /**
     * The property that actually matters, and the one a string check on the
     * sanitised name gets wrong: the file must land **directly inside** the root.
     *
     * A name of `..` sanitises to `..` -- every character is legal in a filename
     * -- and `File(root, "..")` resolves to the root's parent. So the store would
     * read and write beside the directory it was handed, and beside every other
     * store in it. Asserting the sanitised name "contains no traversal" would
     * have passed on the code that had this bug, because `auth_.._tokens` is a
     * perfectly safe filename and it is a *whole component* of `..` that is not.
     */
    @Test
    fun `the file always lands directly inside the root, whatever the name`() {
        val hostile = listOf("..", ".", "../..", "a/../../b", "/etc/passwd", "....", "..\\..", " ")
        for (name in hostile) {
            val store = store(name)
            val parent = File(store.sanitizedName).absoluteFile.parentFile
            val resolved = root.absoluteFile.resolve(store.sanitizedName).canonicalFile
            assertEquals(root.canonicalFile, resolved.canonicalFile.parentFile ?: parent, "name '$name' escaped the root: $resolved")
        }
    }

    @Test
    fun `an empty name still yields a file rather than the directory itself`() {
        // Without the guard this would resolve to the root directory, and every
        // read would then see every other store's keys.
        val store = store("")
        store.putString("k", "v")
        assertEquals("v", store.getString("k", null))
        assertEquals("v", store("").getString("k", null))
    }

    @Test
    fun `keys are sorted, so two stores holding the same data answer identically`() {
        store("ordered").apply {
            putString("zebra", "1")
            putString("alpha", "2")
            putString("mango", "3")
        }
        assertEquals(listOf("alpha", "mango", "zebra"), store("ordered").keys().toList())
    }

    @Test
    fun `contains and remove agree with the stored value`() {
        val store = store("mutations")
        assertFalse(store.contains("gone"))

        store.putString("gone", "here")
        assertTrue(store.contains("gone"))

        store.remove("gone")
        assertFalse(store.contains("gone"))
        assertNull(store.getString("gone", null))
    }

    @Test
    fun `clear empties the store and leaves the file usable`() {
        val store = store("emptied")
        store.putString("a", "1")
        store.putString("b", "2")

        store.clear()
        assertEquals(emptySet(), store.keys())

        store.putString("c", "3")
        assertEquals("3", store.getString("c", null))
        assertEquals(setOf("c"), store.keys())
    }

    /**
     * A file truncated by a hard kill must not stop the app from starting.
     *
     * The fallback is a real trade -- the contents are lost, and the next write
     * overwrites the file -- and it is recorded at the class. What this pins is
     * that the trade is what was chosen, rather than a crash.
     */
    @Test
    fun `an unreadable file reads as empty rather than throwing`() {
        val store = store("corrupt")
        store.putString("k", "v")
        // A malformed unicode escape is the one thing `Properties.load` is
        // documented to reject. A line that merely looks wrong, such as
        // `=not a key`, parses fine as an empty key -- an earlier version of this
        // fixture used one of those and the case passed for the wrong reason.
        File(root, store.sanitizedName).writeText("key=\\uZZZZ")

        assertNull(store.getString("k", null))
        assertEquals(emptySet(), store.keys())

        store.putString("recovered", "yes")
        assertEquals("yes", store.getString("recovered", null))
    }

    @Test
    fun `the store is created if the root directory does not exist`() {
        val fresh = File(root, "does/not/exist/yet")
        val store = FileKeyValueStore(fresh, "playback")
        store.putBoolean("autoplay", true)
        assertTrue(FileKeyValueStore(fresh, "playback").getBoolean("autoplay", false))
    }

    @Test
    fun `a value stored through the interface type behaves identically`() {
        // Written against the port, not the class, so a caller that only knows
        // `KeyValueStore` is covered too.
        val store: KeyValueStore = store("as-port")
        store.putFloat("volume", 0.25f)
        assertEquals(0.25f, store.getFloat("volume", 0f))
    }
}
