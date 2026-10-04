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
/**
 * The store name for the window's own geometry.
 *
 * A **separate store** from everything the graph owns, and the reason is the rule
 * `FileKeyValueStore` already documents: one file per store name, because a prefix
 * has to be re-applied on every read and write and one missed prefix is a silent
 * leak between stores. The window size is incidental to the build; the image
 * quality is the user's. They change on different schedules, and the day one of
 * them wants a schema the other will not want to be in the same file.
 *
 * ## What is *not* named here any more
 *
 * This file used to also declare `IMAGE_SETTINGS_STORE_NAME = "image-settings"`
 * for [com.crispy.tv.settings.ImageSettingsRepository]. The image-quality store is
 * named by `:app`'s `AppGraph`, which is the only reader of it once the screens
 * are real, and the two names disagreed by a hyphen -- two files, two answers to
 * "what quality is this profile using", and the one the real screens read was not
 * the one this module wrote. A name declared in two places is a name that will
 * eventually disagree, so only the window's own name lives here.
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
