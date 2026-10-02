package com.crispy.tv.addons.registry

/**
 * "Lock this method on the JVM", and nothing anywhere else.
 *
 * ## Why not `kotlin.jvm.Synchronized` directly
 *
 * It resolves -- it is an `expect annotation class` in `commonMain`, so
 * `import kotlin.jvm.Synchronized` compiles and every JVM target is genuinely
 * synchronized. **But `compileKotlinLinuxX64` rejects it as an error**, with the
 * deprecation message that tells you what to do instead: *"introduce your own
 * optional-expectation annotation and actualize it with a typealias to
 * `kotlin.jvm.Synchronized`."* This is that annotation. The alternatives were
 * both rejected on their merits rather than on a preference:
 *
 * - **`kotlin.concurrent.Synchronized`** (the newer experimental name) is
 *   **not resolvable at all** in Kotlin 2.4.10 -- the first attempt failed with
 *   `Unresolved reference 'Synchronized'`. So the migration the name suggests
 *   is not available, and the working import is the *old* one.
 * - **A `kotlinx.coroutines.sync.Mutex`** is what `:app`'s `SubtitleRepository`
 *   uses, and it is portable. It was rejected because **`withLock` suspends**,
 *   which would have made all five of this class's public methods `suspend` and
 *   reached every caller. `SubtitleRepository`'s own KDoc records the cost it
 *   paid for exactly that, so the cost is known rather than guessed.
 *
 * ## What this does and does not guarantee
 *
 * On Android it is `kotlin.jvm.Synchronized` through the typealias, so the
 * behaviour is unchanged: `cachedState` and the read-modify-write of the
 * persisted snapshot are mutually excluded, and `cachedState` is `@Volatile` on
 * every target.
 *
 * **On every other target the annotation is absent, so these methods are not
 * mutually excluded.** That is stated here rather than left to be discovered,
 * because the class is not currently reachable from a multi-threaded non-JVM
 * caller: the only construction site is `metadataAddonRegistry(context)` in
 * `androidMain`. If an Apple or desktop caller appears, the fix is a real lock
 * and the methods become `suspend` -- not this annotation.
 */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
expect annotation class JvmSynchronized()