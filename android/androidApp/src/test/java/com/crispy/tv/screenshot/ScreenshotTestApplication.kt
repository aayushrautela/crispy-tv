package com.crispy.tv.screenshot

import android.app.Application

/**
 * Empty Application for the Robolectric unit tests.
 *
 * Robolectric boots whichever Application the merged manifest names, which is
 * [com.crispy.tv.CrispyApplication]. Its `onCreate` starts the mutation outbox,
 * which reaches an `AndroidKeyStore`-backed token store -- and AndroidKeyStore
 * does not exist on a JVM, so every test died during setup before asserting
 * anything.
 *
 * The tests need a theme and nothing else, so the suite swaps in this
 * Application rather than stubbing out the dependency graph. Any test that
 * genuinely needs the real graph should not be a unit test.
 */
class ScreenshotTestApplication : Application()
