package com.crispy.tv.settings

enum class ImageQuality(val displayName: String, val key: String) {
    LOW("Low", "low"),
    MEDIUM("Medium", "medium"),
    HIGH("High", "high");

    companion object {
        fun fromKey(key: String?): ImageQuality {
            return entries.firstOrNull { it.key == key } ?: MEDIUM
        }
    }
}
