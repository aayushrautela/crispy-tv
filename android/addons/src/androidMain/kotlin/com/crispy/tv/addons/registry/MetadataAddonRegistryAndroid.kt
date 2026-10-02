package com.crispy.tv.addons.registry

import android.content.Context
import com.crispy.tv.platform.android.SharedPreferencesKeyValueStore

/**
 * [MetadataAddonRegistry] over `SharedPreferences`, which is the whole of what
 * this module's `androidMain` is left holding.
 *
 * **A `Context` used for wiring belongs in the factory**, so the `Context` and
 * `SharedPreferences` live here and the class takes
 * [`KeyValueStore`][com.crispy.tv.platform.KeyValueStore] and a clock. That is the
 * same rule that moved `CalendarViewModel`'s factory out of its companion, and it
 * is why this is a top-level function rather than a second constructor: a
 * constructor overload taking `Context` would put the platform type back into the
 * class's own signature, which is the thing being removed.
 *
 * `System::currentTimeMillis` is the clock for the same reason `Dispatchers.IO` is
 * a value passed in rather than a default: the registry stamps
 * `addedAtEpochMs`, and the two places that read the wall clock are only testable
 * once it is a parameter.
 *
 * The seven call sites across `:addons`, `:app` and `:androidApp` say
 * `metadataAddonRegistry(context)` rather than `MetadataAddonRegistry(context)`, so
 * the change is one word at each.
 */
fun metadataAddonRegistry(context: Context): MetadataAddonRegistry =
    MetadataAddonRegistry(
        store = SharedPreferencesKeyValueStore(context.applicationContext, MetadataAddonRegistry.STORE_NAME),
        nowMs = System::currentTimeMillis,
    )