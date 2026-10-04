package com.crispy.tv.platform.desktop

import com.crispy.tv.platform.KeyValueStore
import com.crispy.tv.platform.KeyValueStoreFactory
import java.io.File

/**
 * The desktop [KeyValueStoreFactory]: one file per store name under [root].
 *
 * Nothing is cached here, deliberately, and the reason is in [FileKeyValueStore]'s own KDoc: a
 * write through one store must be visible to the next read through another, which is only true if
 * the two handles are not two views of one in-memory copy. The map below caches the *handles*,
 * not their contents, and each handle re-reads its file on every operation.
 */
class FileKeyValueStoreFactory(private val root: File) : KeyValueStoreFactory {
    private val stores = mutableMapOf<String, KeyValueStore>()

    override fun store(name: String): KeyValueStore = stores.getOrPut(name) {
        FileKeyValueStore(root, name)
    }
}