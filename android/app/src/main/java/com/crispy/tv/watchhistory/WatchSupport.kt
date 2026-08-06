package com.crispy.tv.watchhistory

import com.crispy.tv.player.MetadataLabMediaType
import java.util.Locale

fun matchesMediaType(expected: MetadataLabMediaType?, actual: MetadataLabMediaType): Boolean {
    return expected == null || expected == actual
}

fun matchesContentId(candidate: String, targetNormalizedId: String): Boolean {
    return candidate.trim().lowercase(Locale.US) == targetNormalizedId
}
