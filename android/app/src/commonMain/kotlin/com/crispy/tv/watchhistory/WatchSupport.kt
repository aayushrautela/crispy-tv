package com.crispy.tv.watchhistory

import com.crispy.tv.player.MetadataLabMediaType

fun matchesMediaType(expected: MetadataLabMediaType?, actual: MetadataLabMediaType): Boolean {
    return expected == null || expected == actual
}

fun matchesContentId(candidate: String, targetNormalizedId: String): Boolean {
    return candidate.trim().lowercase() == targetNormalizedId
}
