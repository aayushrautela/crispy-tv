package com.crispy.tv.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.addons.registry.metadataAddonRegistry
import com.crispy.tv.network.AppHttp
import kotlinx.coroutines.Dispatchers

/**
 * The Android composition root for [AddonsSettingsViewModel], extracted from the
 * `companion object` it used to carry.
 *
 * This is the third of the same shape -- [catalogViewModelFactory] and
 * [discoverViewModelFactory] were the first two -- and the reason is the same each
 * time. A `companion object` that takes a `Context` is not a companion object, it is a
 * composition root wearing a class's clothes: nothing about the factory is shared, and
 * as a member it is invisible to the question "what does this file reach for", which
 * is the question that decides whether the file can move.
 *
 * `val appContext` is hoisted above the returned object deliberately. Inside the
 * anonymous `Factory` it would be a property of that object, which is re-created per
 * call; hoisted, it is evaluated once per call to *this* function and captured -- and,
 * more usefully, its lifetime is now visibly the factory's rather than the inner
 * class's.
 *
 * `ioDispatcher = Dispatchers.IO` is the last line of the port's own argument for
 * this shape: `Dispatchers.IO` is public on the JVM and `internal` on Kotlin/Native, so
 * writing it in a `commonMain` file compiles everywhere except a Mac and fails first on
 * `apple.yml`. Defaulting it would be worse than leaving it out -- a defaulted
 * `Dispatchers.Default` compiles on every target and silently puts blocking HTTP on a
 * CPU pool sized for compute.
 */
fun addonsSettingsViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AddonsSettingsViewModel::class.java)) {
                val httpClient = AppHttp.client(appContext)
                val addonRegistry = metadataAddonRegistry(appContext)
                @Suppress("UNCHECKED_CAST")
                return AddonsSettingsViewModel(
                    addonRegistry = addonRegistry,
                    httpClient = httpClient,
                    householdAddonsCloudSync =
                        SupabaseServicesProvider.createHouseholdAddonsCloudSync(appContext, addonRegistry),
                    ioDispatcher = Dispatchers.IO,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}