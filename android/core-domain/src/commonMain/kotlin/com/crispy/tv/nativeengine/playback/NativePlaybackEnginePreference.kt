package com.crispy.tv.nativeengine.playback

/**
 * Which playback engine the user prefers.
 *
 * Lives in `:core-domain` rather than beside its sibling `NativePlaybackEngine`
 * in `:android:native-engine` because a *preference* is a stored setting, and
 * [com.crispy.tv.settings.PlaybackSettings] carries this type in its public
 * interface. `:android:native-engine` is a plain `com.android.library`, and a
 * plain Android library cannot be consumed from a KMP `commonMain` at all -- it
 * publishes no JVM variant -- so as long as this enum sat there, every settings
 * type mentioning it was pinned to `androidMain` by the module type, not by
 * anything in the code.
 *
 * `NativePlaybackEngine` itself stays put: it names an engine the module
 * actually constructs, so it is only ever referenced from inside the engine.
 */
enum class NativePlaybackEnginePreference(
    val label: String,
) {
    Auto("Auto"),
    ExoPlayer("ExoPlayer"),
    Libmpv("libmpv"),
}
