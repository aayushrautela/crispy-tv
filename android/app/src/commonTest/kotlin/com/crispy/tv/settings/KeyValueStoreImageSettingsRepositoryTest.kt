package com.crispy.tv.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyValueStoreImageSettingsRepositoryTest {

    @Test
    fun anEmptyStoreYieldsTheDefaultQuality() {
        assertEquals(ImageQuality.MEDIUM, repository().settings.value.quality)
    }

    @Test
    fun aStoredQualityIsReadBack() {
        val store = FakeKeyValueStore(mapOf("image_quality" to "high"))

        assertEquals(ImageQuality.HIGH, KeyValueStoreImageSettingsRepository(store, {}).settings.value.quality)
    }

    @Test
    fun anUnrecognisedStoredKeyFallsBackToTheDefault() {
        val store = FakeKeyValueStore(mapOf("image_quality" to "ultra"))

        assertEquals(ImageQuality.MEDIUM, KeyValueStoreImageSettingsRepository(store, {}).settings.value.quality)
    }

    @Test
    fun aQualityChangeInvalidatesTheImageCacheOnce() {
        val invalidated = mutableListOf<ImageQuality>()
        val repository = KeyValueStoreImageSettingsRepository(FakeKeyValueStore(), { invalidated += it })

        repository.setQuality(ImageQuality.HIGH)

        assertEquals(listOf(ImageQuality.HIGH), invalidated)
    }

    @Test
    fun settingTheSameQualityAgainInvalidatesNothingAndDoesNotWrite() {
        val store = FakeKeyValueStore()
        val invalidated = mutableListOf<ImageQuality>()
        val repository = KeyValueStoreImageSettingsRepository(store, { invalidated += it })

        repository.setQuality(ImageQuality.MEDIUM)

        assertEquals(emptyList(), invalidated)
        assertEquals(emptyList(), store.writes)
    }

    @Test
    fun whatWasWrittenIsReadBackByAFreshRepository() {
        val store = FakeKeyValueStore()
        KeyValueStoreImageSettingsRepository(store, {}).setQuality(ImageQuality.LOW)

        assertEquals(ImageQuality.LOW, KeyValueStoreImageSettingsRepository(store, {}).settings.value.quality)
    }

    private fun repository() = KeyValueStoreImageSettingsRepository(FakeKeyValueStore(), {})
}
