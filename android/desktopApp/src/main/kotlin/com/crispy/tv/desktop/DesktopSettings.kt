package com.crispy.tv.desktop

/**
 * The settings keys and defaults the desktop application uses.
 *
 * ## Why a separate file
 *
 * The keys are a contract between one run and the next, not a detail of the
 * window: a typo in a key silently starts a fresh setting rather than failing,
 * because a settings store has no schema to validate against. Naming them in one
 * place makes them greppable, and the store's own KDoc already explains why
 * nothing here is validated -- there is nothing to validate *against*.
 *
 * The prefixed store name is the other half of the same rule. `FileKeyValueStore`
 * gives each store its own file, so `"settings"` here and a future `"library"`
 * cannot collide however many keys either accumulates. That is why the name is a
 * constant rather than a string repeated at each call site.
 */
internal const val SETTINGS_STORE_NAME: String = "settings"

/** Window width in density-independent pixels. */
internal const val WINDOW_WIDTH_KEY: String = "window.width"

/** Window height in density-independent pixels. */
internal const val WINDOW_HEIGHT_KEY: String = "window.height"

/**
 * The width used on a first run, and whenever the stored value is unreadable.
 *
 * A `Float` rather than an `Int` because the store's accessor is typed and
 * reading a width as a `Float` and using it as an `Int` would be a conversion
 * with no check on it.
 */
internal const val DEFAULT_WINDOW_WIDTH_DP: Float = 1100f

/** The height used on a first run, and whenever the stored value is unreadable. */
internal const val DEFAULT_WINDOW_HEIGHT_DP: Float = 800f
