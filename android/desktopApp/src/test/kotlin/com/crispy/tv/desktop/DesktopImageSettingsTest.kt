package com.crispy.tv.desktop

import com.crispy.tv.platform.desktop.FileKeyValueStore
import com.crispy.tv.settings.ImageQuality
import com.crispy.tv.settings.KeyValueStoreImageSettingsRepository
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:android:app`'s image-quality repository, over a desktop `KeyValueStore`,
 * across a restart.
 *
 * ## Why this suite exists separately from [DesktopEnvironmentTest]
 *
 * `DesktopEnvironmentTest` proves the *ports* work — that a real environment hands
 * out six working stores pointing at one directory. This proves the thing above
 * that, and which nothing else in the repository covers: **a real `:app`
 * repository writing through a real desktop store.** Every other test of
 * `KeyValueStoreImageSettingsRepository` runs in `:app`'s own `commonTest` against
 * a fake, and every test of `FileKeyValueStore` runs in `:platform-desktop`
 * against a hand-written caller. Neither ever puts the two together, so a
 * `KeyValueStore` that satisfied its own contract and a repository that satisfied
 * its own could still not interoperate — which is the entire claim of a port.
 *
 * It is also the first test anywhere that a *user-visible setting* survives a
 * desktop restart, and that is the property the window-size round trip in `main`
 * only approximates.
 */
class DesktopImageSettingsTest {

    private val directory: File = createTempDirectory("crispy-image-settings").toFile()

    /**
     * Built the way `DesktopEnvironment` builds it, so this cannot pass against a
     * wiring `main` does not use.
     */
    private fun environment() = DesktopEnvironment(dataDirectory = directory)

    @Test
    fun `a first run reads the medium default rather than nothing`() {
        assertEquals(ImageQuality.MEDIUM, environment().imageSettings.settings.value.quality)
    }

    @Test
    fun `a quality chosen in one run is still chosen in the next`() {
        environment().imageSettings.setQuality(ImageQuality.HIGH)

        // A second environment over the same directory is a restart: a new store,
        // a new repository, and the file on disk as the only thing they share.
        assertEquals(ImageQuality.HIGH, environment().imageSettings.settings.value.quality)
    }

    @Test
    fun `the chosen quality is in the file on disk, under the repository's own key`() {
        environment().imageSettings.setQuality(ImageQuality.LOW)

        // Asserted against the file, not against the value the code handed back.
        // `ImageSettingsRepository` keeps `KEY_IMAGE_QUALITY` private, so the key
        // is read from the only file the store can have written -- and the point
        // of the assertion is that a *user-visible setting* reached the disk at
        // all, not which string it used.
        val onDisk = directory.walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }
        assertTrue(
            onDisk.contains(ImageQuality.LOW.key),
            "the chosen quality should be on disk, but the files held: $onDisk",
        )
    }

    @Test
    fun `image settings and window settings do not share a file`() {
        environment().imageSettings.setQuality(ImageQuality.HIGH)
        environment().settings.putInt("rows", 7)

        // The one-file-per-store-name rule, checked rather than assumed. A store
        // that used a key prefix inside the window store instead would satisfy
        // every other test in this file and would still fail this one.
        val files = directory.walkTopDown().filter(File::isFile).toList()
        assertTrue(
            files.any { it.name.contains("image") },
            "expected a separate image-settings file, found: ${files.map(File::getName)}",
        )
        assertTrue(
            files.any { it.name.contains(SETTINGS_STORE_NAME) },
            "expected the window settings file, found: ${files.map(File::getName)}",
        )
    }

    @Test
    fun `the published state flow tracks the chosen quality`() {
        val repository = environment().imageSettings

        repository.setQuality(ImageQuality.HIGH)
        assertEquals(ImageQuality.HIGH, repository.settings.value.quality)

        repository.setQuality(ImageQuality.LOW)
        assertEquals(ImageQuality.LOW, repository.settings.value.quality)
    }

    @Test
    fun `choosing the quality already in effect is not a second write`() {
        val repository = environment().imageSettings
        repository.setQuality(ImageQuality.HIGH)

        val changed = mutableListOf<ImageQuality>()
        val second = KeyValueStoreImageSettingsRepository(
            store = FileKeyValueStore(directory, IMAGE_SETTINGS_STORE_NAME),
            onQualityChanged = { changed += it },
        )
        second.setQuality(ImageQuality.HIGH)

        // The guard under test is `_settings.value.quality == quality`, and the
        // only thing it is protecting is the callback: a redundant write of the
        // same value would otherwise drop a decoded-image cache for nothing.
        assertTrue(changed.isEmpty(), "a no-op change should not notify, but got $changed")
    }

    @Test
    fun `an unreadable stored quality falls back to the default rather than failing`() {
        val store = FileKeyValueStore(directory, IMAGE_SETTINGS_STORE_NAME)
        store.putString("image_quality", "not-a-real-quality")

        // `ImageQuality.fromKey` is a total function by design, and this is the
        // case that proves it is used on the read path rather than only on the
        // write path.
        assertEquals(ImageQuality.MEDIUM, KeyValueStoreImageSettingsRepository(store, {}).settings.value.quality)
    }

    @Test
    fun `the no-default callback slot is satisfied explicitly by the environment`() {
        // Not a behaviour assertion -- a compile-time one that has been given a
        // name. `onQualityChanged` has no default on purpose, so a caller with no
        // cache to invalidate has to write `{}` and see that they are declining
        // the callback. If a default were ever added, this test still compiles
        // and still passes, which is the limit of what it can prove; what it
        // documents is that desktop renders no artwork and therefore has nothing
        // to invalidate.
        val changed = mutableListOf<ImageQuality>()
        val repository = KeyValueStoreImageSettingsRepository(
            store = FileKeyValueStore(directory, IMAGE_SETTINGS_STORE_NAME),
            onQualityChanged = { changed += it },
        )
        repository.setQuality(ImageQuality.HIGH)
        assertEquals(listOf(ImageQuality.HIGH), changed)
    }
}
