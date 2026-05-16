package com.crispy.tv.domain

@JvmInline
value class MediaKey(val value: String) {
    val type: String get() = value.substringBefore(':')

    val provider: String get() = value.substringAfter(':').substringBefore(':')

    val referenceId: String get() = value.substringAfter(':').substringAfter(':')

    companion object {
        fun of(type: String, provider: String, id: String): MediaKey =
            MediaKey("$type:$provider:$id")
    }
}
