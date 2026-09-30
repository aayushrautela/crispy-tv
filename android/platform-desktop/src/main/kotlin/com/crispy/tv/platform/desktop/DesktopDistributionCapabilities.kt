package com.crispy.tv.platform.desktop

import com.crispy.tv.platform.DistributionCapabilities

/**
 * The desktop's [DistributionCapabilities].
 *
 * ## Why every value defaults to false
 *
 * All four capabilities are implemented by modules that are Android libraries:
 * the QuickJS plugin runtime (`:android:plugins`), inline YouTube trailer
 * extraction (`:android:youtube-extractor`) and the torrent engine
 * (`:android:torrent-engine`). A plain `kotlin.jvm` module cannot depend on a
 * `com.android.library` at all, so on desktop today none of them can be
 * constructed. `false` is therefore not a placeholder or a stub for something
 * unfinished -- it is the accurate answer, and it is the answer the interface
 * exists to give.
 *
 * The distinction matters because this is exactly the shape that rule about
 * null-returning stubs forbids, and the difference is the intent. A stub stands
 * in for work that ought to be done and hides it. This is a *declaration*: the
 * shared code asks whether a capability exists on this target, and gets a
 * truthful no. The code that asks is the code that would have to branch on
 * `isAndroid()` otherwise, which is the thing the interface was introduced to
 * remove.
 *
 * ## Why the values are constructor parameters
 *
 * So that gaining a capability is a wiring change rather than an edit to a
 * constant. When a portable plugin runtime lands, the composition root passes
 * `pluginsRuntimeAvailable = true` here and every shared call site that already
 * consults the interface starts behaving correctly, with no other change and no
 * new branch.
 *
 * Defaults live in the parameters rather than at the call site so that a
 * partially-capable desktop build cannot be written by accident: the zero-argument
 * construction is the honest current one, and anything else has to be said out
 * loud.
 */
data class DesktopDistributionCapabilities(
    override val pluginsUiSupported: Boolean = false,
    override val pluginsRuntimeAvailable: Boolean = false,
    override val youtubeInHeroPlaybackSupported: Boolean = false,
    override val torrentPlaybackSupported: Boolean = false,
) : DistributionCapabilities
