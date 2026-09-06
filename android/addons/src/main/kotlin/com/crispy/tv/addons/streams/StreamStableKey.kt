package com.crispy.tv.addons.streams

/**
 * Shared addon/plugin stream identity. Providers list rows in one LazyColumn
 * keyed on [AddonStream.stableKey], so both addon and plugin stream parsers
 * must dedupe with the same key shape and derive stable keys from it.
 */
fun buildStreamDedupeKey(vararg parts: String?): String =
    parts.joinToString("|") { it.orEmpty() }

fun buildStreamStableKey(providerId: String, dedupeKey: String): String =
    "$providerId-${dedupeKey.hashCode().toUInt().toString(16)}"
