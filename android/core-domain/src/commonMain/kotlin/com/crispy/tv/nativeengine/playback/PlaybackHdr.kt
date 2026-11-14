package com.crispy.tv.nativeengine.playback

/**
 * The colour transfer functions that carry HDR, spelled exactly as mpv spells
 * them in the `video-params/trc` property.
 *
 * A wide *gamut* is not the test, and thinking it is, is the mistake this set
 * exists to prevent: `bt.2020` primaries with a `bt.1886` transfer function
 * are still ordinary SDR. It is the transfer function that says the signal
 * runs outside the SDR range.
 */
val HDR_TRANSFER_FUNCTIONS: Set<String> = setOf("pq", "hlg", "arib-std-b67", "smpte2084")

/**
 * Whether [trc] names an HDR transfer function.
 *
 * Compared after trimming and lowercasing, because the value arrives as a raw
 * string from the engine and nothing normalises it on the way. A blank or
 * absent value is not HDR: while a file loads the property reads as an empty
 * string, and answering `true` from a missing value would light the HDR
 * button before anything is known about the stream.
 */
fun isHdrTransferFunction(trc: String?): Boolean =
    trc?.trim()?.lowercase() in HDR_TRANSFER_FUNCTIONS