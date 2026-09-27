package com.crispy.tv.screenshot

import android.app.Application

/**
 * Empty Application for the golden-screenshot suite.
 *
 * Robolectric boots whichever Application the merged manifest names, which is
 * [com.crispy.tv.CrispyApplication]. Its `onCreate` starts the mutation outbox,
 * which reaches an `AndroidKeyStore`-backed token store -- and AndroidKeyStore
 * does not exist on a JVM, so every test died during setup before rendering
 * anything.
 *
 * Compose rendering needs a theme and nothing else, so the suite swaps in this
 * Application rather than stubbing out the dependency graph. Any test that
 * genuinely needs the real graph should not be a screenshot test.
 */
class ScreenshotTestApplication : Application()
