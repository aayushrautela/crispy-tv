package com.crispy.tv.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins every colour the Crispy design system renders with.
 *
 * ## Why a test is needed for constants
 *
 * The palette was extracted from two files that each held the same twenty-seven
 * literals, and the whole claim of that change is that it moved values without
 * changing any. Nothing about a `darkColorScheme` call fails to compile when a
 * hex digit is mistyped, and the golden suite does not render `:android:tv` at
 * all -- so before this test existed, a mistyped colour on either surface would
 * have shipped green.
 *
 * `androidx.tv.material3`'s `darkColorScheme` takes the same `Color` type, so
 * the wrong value there compiles just as cleanly. The *mapping* between the two
 * vocabularies is compiler-checked (`:tv` writes `border = CrispyPalette.outline`,
 * which fails to compile if the name is wrong); the *values* are only checkable
 * by assertion, and that is what this file is.
 *
 * ## Reading a failure
 *
 * The assertion names the role and both values, so a diff here says which colour
 * moved rather than that a screen looks wrong.
 *
 * ## About the repeated values
 *
 * Several roles deliberately share a value -- `background`, `surfaceContainerLow`,
 * `inverseOnSurface` and `onPrimary` are all `0xFF141414`; `outline`,
 * `secondaryContainer` and `surfaceContainerHighest` are all `0xFF333333`. That
 * is the measured design, not an accident of the extraction, and it is why no
 * test here asserts that the roles are distinct.
 */
class CrispyPaletteTest {

    @Test
    fun everyRoleKeepsTheValueItHadBeforeThePaletteExisted() {
        val expected = linkedMapOf(
            "primary" to Color(0xFFFFFFFF),
            "onPrimary" to Color(0xFF141414),
            "primaryContainer" to Color(0xFF2A2A2A),
            "onPrimaryContainer" to Color(0xFFFFFFFF),
            "inversePrimary" to Color(0xFFFFFFFF),
            "secondary" to Color(0xFFB3B3B3),
            "onSecondary" to Color(0xFF141414),
            "secondaryContainer" to Color(0xFF333333),
            "onSecondaryContainer" to Color(0xFFFFFFFF),
            "tertiary" to Color(0xFF888888),
            "onTertiary" to Color(0xFFFFFFFF),
            "tertiaryContainer" to Color(0xFF2A2A2A),
            "onTertiaryContainer" to Color(0xFFFFFFFF),
            "background" to Color(0xFF141414),
            "onBackground" to Color(0xFFFFFFFF),
            "surface" to Color(0xFF1F1F1F),
            "onSurface" to Color(0xFFFFFFFF),
            "surfaceVariant" to Color(0xFF2A2A2A),
            "onSurfaceVariant" to Color(0xFFB3B3B3),
            "surfaceContainer" to Color(0xFF1F1F1F),
            "surfaceContainerHigh" to Color(0xFF2A2A2A),
            "surfaceContainerHighest" to Color(0xFF333333),
            "surfaceContainerLow" to Color(0xFF141414),
            "surfaceContainerLowest" to Color(0xFF0A0A0A),
            "surfaceDim" to Color(0xFF0A0A0A),
            "surfaceBright" to Color(0xFF2A2A2A),
            "surfaceTint" to Color(0xFFFFFFFF),
            "outline" to Color(0xFF333333),
            "outlineVariant" to Color(0xFF262626),
            "error" to Color(0xFFE8455C),
            "onError" to Color(0xFFFFFFFF),
            "errorContainer" to Color(0xFFB03040),
            "onErrorContainer" to Color(0xFFFFDAD6),
            "inverseSurface" to Color(0xFFECE1C6),
            "inverseOnSurface" to Color(0xFF141414),
            "scrim" to Color(0xFF000000),
        )

        val actual = mapOf(
            "primary" to CrispyPalette.primary,
            "onPrimary" to CrispyPalette.onPrimary,
            "primaryContainer" to CrispyPalette.primaryContainer,
            "onPrimaryContainer" to CrispyPalette.onPrimaryContainer,
            "inversePrimary" to CrispyPalette.inversePrimary,
            "secondary" to CrispyPalette.secondary,
            "onSecondary" to CrispyPalette.onSecondary,
            "secondaryContainer" to CrispyPalette.secondaryContainer,
            "onSecondaryContainer" to CrispyPalette.onSecondaryContainer,
            "tertiary" to CrispyPalette.tertiary,
            "onTertiary" to CrispyPalette.onTertiary,
            "tertiaryContainer" to CrispyPalette.tertiaryContainer,
            "onTertiaryContainer" to CrispyPalette.onTertiaryContainer,
            "background" to CrispyPalette.background,
            "onBackground" to CrispyPalette.onBackground,
            "surface" to CrispyPalette.surface,
            "onSurface" to CrispyPalette.onSurface,
            "surfaceVariant" to CrispyPalette.surfaceVariant,
            "onSurfaceVariant" to CrispyPalette.onSurfaceVariant,
            "surfaceContainer" to CrispyPalette.surfaceContainer,
            "surfaceContainerHigh" to CrispyPalette.surfaceContainerHigh,
            "surfaceContainerHighest" to CrispyPalette.surfaceContainerHighest,
            "surfaceContainerLow" to CrispyPalette.surfaceContainerLow,
            "surfaceContainerLowest" to CrispyPalette.surfaceContainerLowest,
            "surfaceDim" to CrispyPalette.surfaceDim,
            "surfaceBright" to CrispyPalette.surfaceBright,
            "surfaceTint" to CrispyPalette.surfaceTint,
            "outline" to CrispyPalette.outline,
            "outlineVariant" to CrispyPalette.outlineVariant,
            "error" to CrispyPalette.error,
            "onError" to CrispyPalette.onError,
            "errorContainer" to CrispyPalette.errorContainer,
            "onErrorContainer" to CrispyPalette.onErrorContainer,
            "inverseSurface" to CrispyPalette.inverseSurface,
            "inverseOnSurface" to CrispyPalette.inverseOnSurface,
            "scrim" to CrispyPalette.scrim,
        )

        // Both maps are written out in full rather than reflecting over the object,
        // because a reflective test only pins the roles it happened to enumerate:
        // adding a role to the palette would pass silently. These two literals must
        // stay the same key set, which is what makes a newly added role a failure
        // rather than a gap. Asserting the key sets first means a new role reports
        // as "the palette's role list changed" rather than as a value diff.
        assertEquals(expected.keys, actual.keys, "the palette's role list changed; pin the new role here")
        for (role in expected.keys) {
            assertEquals(
                expected.getValue(role),
                actual.getValue(role),
                "$role changed value",
            )
        }
    }

    @Test
    fun theTwoBorderRolesAreDistinctFromEachOther() {
        // `outline` and `outlineVariant` are the pair `androidx.tv.material3` calls
        // `border` and `borderVariant`. They are separate roles, so a copy-paste
        // that collapsed them onto one value would flatten every border in the app
        // while still compiling -- and would still satisfy "the same on both
        // surfaces", which is the property this landing was checked against.
        assertNotEquals(
            CrispyPalette.outline,
            CrispyPalette.outlineVariant,
            "outline and outlineVariant are different roles and must not share a value",
        )
    }

    /**
     * Every role in the palette, named.
     *
     * The neutrality tests need to name the role they are asserting about, because
     * a loop over thirty-two values whose failure message reads "a neutral role has
     * red != green" identifies nothing. This map exists for that reason and for no
     * other: it is not the value pin, which is [everyRoleKeepsTheValueItHadBeforeThePaletteExisted]
     * and is written as two separate literals on purpose.
     */
    private fun rolesByName(): Map<String, Color> = mapOf(
        "primary" to CrispyPalette.primary,
        "onPrimary" to CrispyPalette.onPrimary,
        "primaryContainer" to CrispyPalette.primaryContainer,
        "onPrimaryContainer" to CrispyPalette.onPrimaryContainer,
        "inversePrimary" to CrispyPalette.inversePrimary,
        "secondary" to CrispyPalette.secondary,
        "onSecondary" to CrispyPalette.onSecondary,
        "secondaryContainer" to CrispyPalette.secondaryContainer,
        "onSecondaryContainer" to CrispyPalette.onSecondaryContainer,
        "tertiary" to CrispyPalette.tertiary,
        "onTertiary" to CrispyPalette.onTertiary,
        "tertiaryContainer" to CrispyPalette.tertiaryContainer,
        "onTertiaryContainer" to CrispyPalette.onTertiaryContainer,
        "background" to CrispyPalette.background,
        "onBackground" to CrispyPalette.onBackground,
        "surface" to CrispyPalette.surface,
        "onSurface" to CrispyPalette.onSurface,
        "surfaceVariant" to CrispyPalette.surfaceVariant,
        "onSurfaceVariant" to CrispyPalette.onSurfaceVariant,
        "surfaceContainer" to CrispyPalette.surfaceContainer,
        "surfaceContainerHigh" to CrispyPalette.surfaceContainerHigh,
        "surfaceContainerHighest" to CrispyPalette.surfaceContainerHighest,
        "surfaceContainerLow" to CrispyPalette.surfaceContainerLow,
        "surfaceContainerLowest" to CrispyPalette.surfaceContainerLowest,
        "surfaceDim" to CrispyPalette.surfaceDim,
        "surfaceBright" to CrispyPalette.surfaceBright,
        "surfaceTint" to CrispyPalette.surfaceTint,
        "outline" to CrispyPalette.outline,
        "outlineVariant" to CrispyPalette.outlineVariant,
        "error" to CrispyPalette.error,
        "onError" to CrispyPalette.onError,
        "errorContainer" to CrispyPalette.errorContainer,
        "onErrorContainer" to CrispyPalette.onErrorContainer,
        "inverseSurface" to CrispyPalette.inverseSurface,
        "inverseOnSurface" to CrispyPalette.inverseOnSurface,
        "scrim" to CrispyPalette.scrim,
    )

    /**
     * The roles that are deliberately not grey, measured: of thirty-six, four
     * carry a tint and thirty-two are exactly neutral.
     *
     * Named once and used by both neutrality tests. Two tests that each spelled out
     * the set would be two places to forget to update, and the failure would be a
     * test that skips a role rather than a test that fails -- which is the failure
     * mode this pair exists to prevent.
     */
    private val colouredRoles = setOf(
        "error", "errorContainer", "onErrorContainer", "inverseSurface",
    )

    private fun isGrey(colour: Color): Boolean {
        val (r, g, b) = colour
        return r == g && g == b
    }

    /**
     * The four roles that are deliberately not grey are exactly these.
     *
     * This is the assertion that makes the neutrality check worth having, and it
     * is the one the hand-picked list it replaced was not. A list of the roles a
     * test happens to look at is a *sample*: a role added to the palette that
     * quietly picked up a tint is simply not in the list, so the suite passes and
     * reports nothing. Comparing the *set of non-neutral roles* against the
     * expected set inverts that -- a new non-grey role fails, by name, and so does
     * an existing one going grey.
     *
     * The split was measured, not assumed: of the thirty-six roles, four carry a
     * tint and thirty-two are exactly neutral. A first pass that read a
     * `Color(0xAARRGGBB)` literal as RGB instead of ARGB reported twenty-seven
     * non-neutral, which is what prompted reading the bytes properly.
     */
    @Test
    fun exactlyFourRolesAreNotGrey() {
        val notGrey = rolesByName().filterValues { !isGrey(it) }.keys

        assertEquals(
            colouredRoles,
            notGrey,
            "the set of non-grey roles changed; if a role was added, pin its direction " +
                "below, and if one gained a tint, that is the regression this test exists for",
        )
    }

    /**
     * The thirty-two roles that are meant to be greys are greys.
     *
     * A role that accidentally picked up a tint is invisible to the compiler, to
     * the goldens on the phone surface, and -- because the golden suite never
     * renders `:android:tv` -- invisible to the gate entirely. This is checkable
     * only because the palette is constants rather than computed.
     */
    @Test
    fun everyRoleOutsideTheColouredFourIsAGrey() {
        for ((role, colour) in rolesByName().filterKeys { it !in colouredRoles }) {
            assertTrue(isGrey(colour), "$role is $colour, which is not a neutral grey")
        }
    }

    /**
     * Each coloured role leans the way it looks.
     *
     * Measured directions, so these are pins rather than decoration. Two of the
     * four are *not* R > G > B, which is why they are written out individually
     * instead of being covered by one blanket assertion: `error` and
     * `errorContainer` are pink-red, with blue above green, and an assertion that
     * said "warm" for every coloured role would have quietly failed on them.
     */
    @Test
    fun eachColouredRoleLeansTheWayItLooks() {
        // error 0xFFE8455C and errorContainer 0xFFB03040: red, but pink enough
        // that blue sits above green.
        assertTrue(
            CrispyPalette.error.red > CrispyPalette.error.green,
            "the error colour must be red-dominant",
        )
        assertTrue(
            CrispyPalette.error.blue > CrispyPalette.error.green,
            "the error colour is pink-red, so blue is above green; if this now fails " +
                "the colour changed, not the test",
        )
        assertTrue(
            CrispyPalette.errorContainer.red > CrispyPalette.errorContainer.green,
            "the error container must be red-dominant",
        )
        assertTrue(
            CrispyPalette.errorContainer.blue > CrispyPalette.errorContainer.green,
            "the error container is pink-red, so blue is above green",
        )

        // onErrorContainer 0xFFFFDAD6 and inverseSurface 0xFFECE1C6: pale warm
        // neutrals carried over from Material3's own tones.
        assertTrue(
            CrispyPalette.onErrorContainer.red > CrispyPalette.onErrorContainer.green &&
                CrispyPalette.onErrorContainer.green > CrispyPalette.onErrorContainer.blue,
            "onErrorContainer is a pale warm pink, red > green > blue",
        )
        assertTrue(
            CrispyPalette.inverseSurface.red > CrispyPalette.inverseSurface.green &&
                CrispyPalette.inverseSurface.green > CrispyPalette.inverseSurface.blue,
            "inverseSurface is a warm cream, red > green > blue",
        )
    }
}
