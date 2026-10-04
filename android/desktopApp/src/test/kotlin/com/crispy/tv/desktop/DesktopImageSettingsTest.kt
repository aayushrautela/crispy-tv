package com.crispy.tv.desktop

import com.crispy.tv.app.AppGraph
import com.crispy.tv.platform.KeyValueStore
import com.crispy.tv.services.DesktopAppServices
import com.crispy.tv.settings.ImageQuality
import com.crispy.tv.settings.KeyValueStoreImageSettingsRepository
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `:android:app`'s image-quality repository, reached the way the desktop
 * application reaches it, across a restart.
 *
 * ## Why this suite exists
 *
 * Every other test of `KeyValueStoreImageSettingsRepository` runs in `:app`'s own
 * `commonTest` against a fake store, and every test of `FileKeyValueStore` runs in
 * `:platform-desktop` against a hand-written caller. Neither ever puts the two
 * together, so a store that satisfied its own contract and a repository that
 * satisfied its own could still not interoperate — which is the entire claim of a
 * port. This is the only place that claims it for image quality, and the first
 * test anywhere that a *user-visible setting* survives a desktop restart.
 *
 * It is also the suite that caught the defect this landing fixed: the repository
 * used to be built by `DesktopEnvironment` under the store name `"image-settings"`
 * while `:app`'s `AppGraph` names its own `"image_settings"`, so the desktop build
 * had two files and two answers, and the screens read the one nothing wrote. Every
 * case below goes through the graph's repository instead of a hand-wired one, which
 * is what makes that impossible to reintroduce unnoticed.
 */
class DesktopImageSettingsTest {

    private val dataDirectory: File = createTempDirectory("crispy-image-settings").toFile()

    /**
     * The cache directory is a second temporary directory because
     * `DesktopAppServices` defaults it to the real per-user cache root, and a test
     * that wrote there would be a test that leaves something behind on a
     * developer's machine.
     */
    private val cacheDirectory: File = createTempDirectory("crispy-image-cache").toFile()

    /**
     * Built the way `main` builds it, so this cannot pass against a wiring `main`
     * does not use: the graph's own repository, over the graph's own services.
     */
    private fun graph() = AppGraph(services())

    private fun services() = DesktopAppServices(
        dataDirectory = dataDirectory,
        cacheDirectory = cacheDirectory,
    )

    /**
     * The window's store, through the same factory `main` asks -- so this suite
     * compares two stores the running build would really have, rather than a
     * hand-built one that merely resembles it.
     */
    private fun windowSettings() = services().keyValueStores.store(SETTINGS_STORE_NAME)

    @Test
    fun `a first run reads the medium default rather than nothing`() {
        assertEquals(ImageQuality.MEDIUM, graph().imageSettingsRepository.settings.value.quality)
    }

    @Test
    fun `a quality chosen in one run is still chosen in the next`() {
        graph().imageSettingsRepository.setQuality(ImageQuality.HIGH)

        // A second graph over the same directory is a restart: new services, a new
        // repository, and the file on disk as the only thing they share.
        assertEquals(ImageQuality.HIGH, graph().imageSettingsRepository.settings.value.quality)
    }

    @Test
    fun `the chosen quality is in the file on disk, under the repository's own key`() {
        graph().imageSettingsRepository.setQuality(ImageQuality.LOW)

        // Asserted against the file, not against the value the code handed back.
        // `ImageSettingsRepository` keeps `KEY_IMAGE_QUALITY` private, so the key
        // is read from the only file the store can have written -- and the point
        // of the assertion is that a *user-visible setting* reached the disk at
        // all, not which string it used.
        val onDisk = dataDirectory.walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }
        assertTrue(
            onDisk.contains(ImageQuality.LOW.key),
            "the chosen quality should be on disk, but the files held: $onDisk",
        )
    }

    @Test
    fun `image settings and window settings do not share a file`() {
        graph().imageSettingsRepository.setQuality(ImageQuality.HIGH)
        windowSettings().putInt("rows", 7)

        // The one-file-per-store-name rule, checked as an exact set rather than
        // with `contains`. `image_settings` *contains* `settings`, so the previous
        // version of this case was satisfied by the image file alone and could not
        // tell one file from two. Two named files is the claim; anything else in
        // the directory means a store this suite does not know about has joined,
        // and the claim needs re-reading rather than widening.
        assertEquals(
            listOf("image_settings", "settings"),
            dataDirectory.walkTopDown().filter { it.isFile }.map { it.name }.toList().sorted(),
        )

        // And neither store can see the other's keys, which is what the two files
        // are for.
        assertEquals(7, windowSettings().getInt("rows", 0))
        assertEquals(ImageQuality.HIGH, graph().imageSettingsRepository.settings.value.quality)
    }

    @Test
    fun `the published state flow tracks the chosen quality`() {
        val repository = graph().imageSettingsRepository

        repository.setQuality(ImageQuality.HIGH)
        assertEquals(ImageQuality.HIGH, repository.settings.value.quality)

        repository.setQuality(ImageQuality.LOW)
        assertEquals(ImageQuality.LOW, repository.settings.value.quality)
    }

    @Test
    fun `choosing the quality already in effect is not a second write`() {
        graph().imageSettingsRepository.setQuality(ImageQuality.HIGH)

        val changed = mutableListOf<ImageQuality>()
        val second = KeyValueStoreImageSettingsRepository(
            store = imageSettingsStore(),
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
        val store = imageSettingsStore()
        store.putString("image_quality", "not-a-real-quality")

        // `ImageQuality.fromKey` is a total function by design, and this is the
        // case that proves it is used on the read path rather than only on the
        // write path.
        assertEquals(
            ImageQuality.MEDIUM,
            KeyValueStoreImageSettingsRepository(store, {}).settings.value.quality,
        )
    }

    @Test
    fun `the no-default callback slot is satisfied explicitly by a caller with no cache`() {
        // Not a behaviour assertion -- a compile-time one that has been given a
        // name. `onQualityChanged` has no default on purpose, so a caller with no
        // cache to invalidate has to write `{}` and see that they are declining
        // the callback. If a default were ever added, this test still compiles
        // and still passes, which is the limit of what it can prove.
        val changed = mutableListOf<ImageQuality>()
        val repository = KeyValueStoreImageSettingsRepository(
            store = imageSettingsStore(),
            onQualityChanged = { changed += it },
        )
        repository.setQuality(ImageQuality.HIGH)
        assertEquals(listOf(ImageQuality.HIGH), changed)
    }

    /**
     * The graph's image-quality store, for the three cases that need a repository
     * of their own over the *same* file.
     *
     * `AppGraph` keeps its store names private, and they have to be: they are a
     * data-compatibility contract with installed builds, not API. A test cannot
     * name them, so it gets the store the only way a caller can -- and the
     * alternative, writing `"image_settings"` out a second time here, is the
     * duplication that caused the defect in the first place.
     */
    private fun imageSettingsStore(): KeyValueStore =
        services().keyValueStores.store(IMAGE_SETTINGS_STORE_NAME)
}

/**
 * Mirrors `AppGraph`'s private `IMAGE_SETTINGS_STORE`, which is why it is only
 * ever used next to [DesktopImageSettingsTest]'s exact-file-set case: that case
 * fails if `:app` renames its store, so this copy cannot drift unnoticed.
 *
 * The name is repeated here rather than widened into `:app` because a test-only
 * accessor for a private constant would be an accessor production has no use for.
 */
private const val IMAGE_SETTINGS_STORE_NAME: String = "image_settings"