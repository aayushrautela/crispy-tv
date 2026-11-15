package com.crispy.tv.ai

// The three text slots a story slide prints, and the rules for what counts as
// printable. Both story surfaces -- the phone overlay and the TV story -- read
// the slots from here, so the two cannot drift on trimming, or on which field
// stands in for which.
//
// The slots are built from disjoint sources (`label`, `heading`, `body`), so a
// slide can never print the same sentence twice.

/**
 * The eyebrow line: the slide's own `label` ("Standout", "The catch"),
 * trimmed, or null when the server sent none.
 */
fun AiInsightSlide.storyLabel(): String? =
    label.trim().takeIf { it.isNotEmpty() }

/**
 * The headline line: the server's `heading`, trimmed, or null when it sent
 * none -- or sent only spaces.
 */
fun AiInsightSlide.storyHeading(): String? =
    heading?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The paragraph line: `body`, trimmed, or null when there is none. Built from
 * `body` only, so it can never repeat [storyLabel] or [storyHeading], which
 * are built from `label`/`heading`. The no-double-print rule is structural,
 * not defensive.
 */
fun AiInsightSlide.storyParagraph(): String? =
    body?.trim()?.takeIf { it.isNotEmpty() }
