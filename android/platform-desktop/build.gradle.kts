plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

/**
 * The desktop implementations of the `:android:platform-core` interfaces.
 *
 * ## Why this is a module and not a package in `:desktopApp`
 *
 * The same argument `:android:platform-android` makes for the Android side, and
 * it is the reason the interfaces were extracted at all: `:desktopApp` is an
 * application, so anything it declared would be reachable only from it. A
 * library module that needs a clock, a logger or a settings store would then
 * have to depend on the application to name the type, which is the dependency
 * direction the whole migration is undoing.
 *
 * ## Why it is a plain `kotlin.jvm` module and not a KMP library
 *
 * Every implementation here is a JVM call, so there is nothing portable to put
 * in a `commonMain` -- the same reasoning that makes `:platform-android` a
 * plain `com.android.library`. Declaring iOS targets here would ship a module
 * whose Apple source set is empty, and when Apple needs implementations they
 * arrive in Phase 6 against these same `platform-core` interfaces, in their own
 * module, exactly as Android's arrived in their own.
 *
 * Converting `:android:platform-android` instead was considered and measured
 * out: five modules already depend on it (`:android:app`, `:android:tv`, and
 * `:android:watchhistory`, `:android:backend`, `:android:home` by `api`), and
 * all four KMP consumers take it from an `androidMain` source set already.
 * Converting it would have churned four modules to move code that has no reason
 * to move.
 */
dependencies {
    // `api`, for the same reason `:platform-android` uses it: a consumer
    // injects these types into its own constructors, so `platform-core` has to
    // be on the consumer's own compile classpath.
    api(project(":android:platform-core"))

    testImplementation(kotlin("test"))
}
