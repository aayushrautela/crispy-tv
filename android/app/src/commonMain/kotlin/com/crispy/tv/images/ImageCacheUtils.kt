package com.crispy.tv.images

import coil3.PlatformContext
import coil3.SingletonImageLoader

/**
 * Drops Coil's in-memory and on-disk image caches.
 *
 * Called when the user changes image quality or signs an account out, so the next
 * screen renders at the new setting instead of serving the previous one's bitmaps.
 *
 * **The pin in this file was the accessor, not the type.** `coil3.imageLoader` is an
 * `actual`: an extension on `android.content.Context` declared in Coil's `androidMain`.
 * There is nothing of that name to call from `commonMain`, which is why
 * `details/DetailsSeedColorLoader.kt` states in its own KDoc that it has "nothing to call
 * from `commonMain`". The shared-instance accessor that Coil *does* declare in its
 * `commonMain` is [SingletonImageLoader].
 *
 * **The other common declaration is not a substitute, and substituting it would be a
 * silent no-op.** Coil's `commonMain` also declares `fun ImageLoader(context:
 * PlatformContext): ImageLoader` — a *factory* that builds a new loader with its own
 * fresh, empty memory and disk caches. Both names answer "give me an ImageLoader", so
 * they are interchangeable to a reader, but the factory would hand back a throwaway
 * loader whose caches are already empty: every `?.clear()` below would succeed and
 * nothing on screen would be evicted. The two differ in *identity*, not in signature —
 * which is the property a mechanical port checks last and a cache-clearing function
 * depends on entirely.
 *
 * [PlatformContext] is Coil's `expect abstract class` for "the platform's base context",
 * with an `actual typealias` to `android.content.Context` on Android. That is why the
 * three call sites in `androidMain` pass a real `Context` and needed no edit: the pin was
 * the declared type, not the type the callers already had.
 */
internal fun clearImageCache(context: PlatformContext) {
    val imageLoader = SingletonImageLoader.get(context)
    imageLoader.memoryCache?.clear()
    imageLoader.diskCache?.clear()
}
