package com.crispy.tv.tv.ui.screens

import com.crispy.tv.tv.ui.components.CrispyCardItem

private val sampleTitles = listOf(
    "Dune: Part Two",
    "The Last of Us",
    "Oppenheimer",
    "Severance",
    "Blade Runner 2049",
    "Shogun",
    "Interstellar",
    "Chernobyl",
)

internal fun sampleRail(prefix: String): List<CrispyCardItem> =
    sampleTitles.mapIndexed { index, title ->
        CrispyCardItem(
            id = "$prefix-$index",
            title = title,
            subtitle = if (index % 3 == 0) "Movie" else "Series",
            progressFraction = if (prefix == "cw") (0.15f * (index + 1)).coerceAtMost(0.95f) else null,
        )
    }
