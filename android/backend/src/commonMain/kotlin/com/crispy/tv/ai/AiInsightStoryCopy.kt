package com.crispy.tv.ai

// The three text slots a story slide prints, and the rules for what counts as
// printable. Both story surfaces -- the phone overlay and the TV story -- read
// the slots from here, so the two cannot drift on trimming, or on which field
// stands in for which.
//
// The slots are built from disjoint sources (`label`, `focus`, `body`/`context`),
// so a slide can never print the same sentence twice.

/**
 * The heading line: the slide's own `label` ("Standout", "The catch"),
 * trimmed, or null when the server sent none.
 */
fun AiInsightSlide.storyLabel(): String? =
    label.trim().takeIf { it.isNotEmpty() }

/**
 * The headline line: the server's `focus`, trimmed, or null when it sent none
 * -- or sent only spaces.
 */
fun AiInsightSlide.storyFocus(): String? =
    focus?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The paragraph line: `body`, or `context` when there is no body. Built from
 * `body`/`context` only, so it can never repeat [storyLabel] or [storyFocus],
 * which are built from `label`/`focus`. The no-double-print rule is
 * structural, not defensive.
 */
fun AiInsightSlide.storyParagraph(): String? =
    (body ?: context)?.trim()?.takeIf { it.isNotEmpty() }
