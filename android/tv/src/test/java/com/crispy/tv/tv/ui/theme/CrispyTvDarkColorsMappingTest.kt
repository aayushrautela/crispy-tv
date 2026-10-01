package com.crispy.tv.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme
import com.crispy.tv.ui.theme.CrispyPalette
import org.junit.Assert.assertEquals

import org.junit.Test

/**
 * Pins the mapping between the shared token layer and the TV surface.
 *
 * ## Why this file exists
 *
 * `AGENTS.md` recorded this mapping as the one untested decision left by Workstream A,
 * and gave the reason: *`:tv` is a plain `com.android.application`*. That reason was a
 * claim, and it is wrong. `CrispyTvDarkColors` is a top-level `val` calling
 * `darkColorScheme(...)`, which builds a `ColorScheme` data class out of
 * `androidx.compose.ui.graphics.Color` — an inline value class over `ULong`. Nothing
 * here touches a `Context`, a resource or a view, so a plain JVM unit test reaches it.
 * The real blocker was that the `val` was `private` and no `src/test` existed; both are
 * fixed in the same landing that adds this file.
 *
 * ## What a value assertion here can and cannot prove
 *
 * **A dropped role is caught, because a dropped role falls back to the library's own
 * default.** `darkColorScheme` supplies a default for every parameter it is not given,
 * so omitting a role does not fail to compile — it silently produces a different colour.
 * `noMappedRoleCoincidesWithTheTvLibrarysOwnDefault` is the case that makes the
 * per-role assertions mean something: if a palette value happened to equal the library
 * default for that role, the assertion would pass whether or not the role was mapped.
 * That is checked rather than assumed, which is why it is a test and not a comment.
 *
 * **A swap among value-equal roles is *not* caught, and cannot be.** Ten of the
 * palette's 37 roles carry the identical value `0xFFFFFFFF` — `primary`,
 * `onPrimaryContainer`, `inversePrimary`, `onSecondaryContainer`, `onTertiary`,
 * `onTertiaryContainer`, `onBackground`, `onSurface`, `onError`, `surfaceTint`. Wiring
 * `onError` to `onBackground` would leave every assertion in this file green. So the
 * suite pins **which value each role carries**, and it says here that this is weaker
 * than "the test asserts every role" sounds. "The test asserts every role" and "the
 * test detects any wrong wiring" are different claims, and only the first is true.
 *
 * **The two renamed roles are the entire cross-library mapping.** `androidx.compose.material3`
 * calls the border roles `outline` and `outlineVariant`; `androidx.tv.material3` calls
 * them `border` and `borderVariant`. Those two lines are written out rather than derived
 * because a missing or swapped mapping is a silent colour change, never a compile error —
 * and the pair is observable, which is what makes it worth a test: `outline` is
 * `0xFF333333` and `outlineVariant` is `0xFF262626`.
 *
 * **Sets are compared as sets, and keys before values.** A hand-picked list of roles is a
 * sample: a role added to `CrispyPalette` and quietly dropped from the mapping would not
 * fail a per-role test. So the role sets are compared as sets, keys first, so a new role
 * reads as "the mapping is missing a role" rather than as a confusing value diff.
 *
 * **The mapping table is keyed by *palette role name*, not by value.** Subtracting one
 * set from another by value is the trap this file would otherwise have walked into:
 * `surfaceContainer` holds `0xFF1F1F1F`, the same value as the mapped `surface`, so a
 * value-based set difference would delete it from the unmapped list and make the
 * "eight roles have no slot" assertion pass for the wrong reason.
 */
class CrispyTvDarkColorsMappingTest {

    private companion object {
        /**
         * The value ten palette roles share, and therefore the value a swap among them
         * is invisible behind. Named once so the case below and the class KDoc refer to
         * the same thing.
         */
        val WHITE = Color(0xFFFFFFFF)
    }

    private val scheme = CrispyTvDarkColors

    /** What `androidx.tv.material3` uses when a role is not passed. */
    private val tvDefaults = darkColorScheme()

    /**
     * TV role name → the `CrispyPalette` role it is drawn from.
     *
     * Keyed by palette role name because that is what makes the unmapped set derivable
     * by name rather than by value, and because 27 of the 29 entries are the identity —
     * only the two border roles are a real decision, and they are marked below.
     */
    private val tvRoleToPaletteRole: Map<String, String> = mapOf(
        "primary" to "primary",
        "onPrimary" to "onPrimary",
        "primaryContainer" to "primaryContainer",
        "onPrimaryContainer" to "onPrimaryContainer",
        "inversePrimary" to "inversePrimary",
        "secondary" to "secondary",
        "onSecondary" to "onSecondary",
        "secondaryContainer" to "secondaryContainer",
        "onSecondaryContainer" to "onSecondaryContainer",
        "tertiary" to "tertiary",
        "onTertiary" to "onTertiary",
        "tertiaryContainer" to "tertiaryContainer",
        "onTertiaryContainer" to "onTertiaryContainer",
        "background" to "background",
        "onBackground" to "onBackground",
        "surface" to "surface",
        "onSurface" to "onSurface",
        "surfaceVariant" to "surfaceVariant",
        "onSurfaceVariant" to "onSurfaceVariant",
        "surfaceTint" to "surfaceTint",
        "inverseSurface" to "inverseSurface",
        "inverseOnSurface" to "inverseOnSurface",
        // The two roles the two libraries name differently. The whole cross-library
        // mapping, and the only two entries in this table that are not the identity.
        "border" to "outline",
        "borderVariant" to "outlineVariant",
        "error" to "error",
        "onError" to "onError",
        "errorContainer" to "errorContainer",
        "onErrorContainer" to "onErrorContainer",
        "scrim" to "scrim",
    )

    /** Every role `CrispyPalette` declares, by name. */
    private val paletteRoles: Set<String> = setOf(
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
        "inversePrimary", "secondary", "onSecondary", "secondaryContainer",
        "onSecondaryContainer", "tertiary", "onTertiary", "tertiaryContainer",
        "onTertiaryContainer", "background", "onBackground", "surface", "onSurface",
        "surfaceVariant", "onSurfaceVariant", "surfaceContainer", "surfaceContainerHigh",
        "surfaceContainerHighest", "surfaceContainerLow", "surfaceContainerLowest",
        "surfaceDim", "surfaceBright", "surfaceTint", "outline", "outlineVariant",
        "error", "onError", "errorContainer", "onErrorContainer", "inverseSurface",
        "inverseOnSurface", "scrim", "spinner",
    )

    private fun paletteValue(role: String): Color = when (role) {
        "primary" -> CrispyPalette.primary
        "onPrimary" -> CrispyPalette.onPrimary
        "primaryContainer" -> CrispyPalette.primaryContainer
        "onPrimaryContainer" -> CrispyPalette.onPrimaryContainer
        "inversePrimary" -> CrispyPalette.inversePrimary
        "secondary" -> CrispyPalette.secondary
        "onSecondary" -> CrispyPalette.onSecondary
        "secondaryContainer" -> CrispyPalette.secondaryContainer
        "onSecondaryContainer" -> CrispyPalette.onSecondaryContainer
        "tertiary" -> CrispyPalette.tertiary
        "onTertiary" -> CrispyPalette.onTertiary
        "tertiaryContainer" -> CrispyPalette.tertiaryContainer
        "onTertiaryContainer" -> CrispyPalette.onTertiaryContainer
        "background" -> CrispyPalette.background
        "onBackground" -> CrispyPalette.onBackground
        "surface" -> CrispyPalette.surface
        "onSurface" -> CrispyPalette.onSurface
        "surfaceVariant" -> CrispyPalette.surfaceVariant
        "onSurfaceVariant" -> CrispyPalette.onSurfaceVariant
        "surfaceContainer" -> CrispyPalette.surfaceContainer
        "surfaceContainerHigh" -> CrispyPalette.surfaceContainerHigh
        "surfaceContainerHighest" -> CrispyPalette.surfaceContainerHighest
        "surfaceContainerLow" -> CrispyPalette.surfaceContainerLow
        "surfaceContainerLowest" -> CrispyPalette.surfaceContainerLowest
        "surfaceDim" -> CrispyPalette.surfaceDim
        "surfaceBright" -> CrispyPalette.surfaceBright
        "surfaceTint" -> CrispyPalette.surfaceTint
        "outline" -> CrispyPalette.outline
        "outlineVariant" -> CrispyPalette.outlineVariant
        "error" -> CrispyPalette.error
        "onError" -> CrispyPalette.onError
        "errorContainer" -> CrispyPalette.errorContainer
        "onErrorContainer" -> CrispyPalette.onErrorContainer
        "inverseSurface" -> CrispyPalette.inverseSurface
        "inverseOnSurface" -> CrispyPalette.inverseOnSurface
        "scrim" -> CrispyPalette.scrim
        "spinner" -> CrispyPalette.spinner
        else -> error("no CrispyPalette property for role '$role'")
    }

    private fun schemeValue(tvRole: String): Color = when (tvRole) {
        "primary" -> scheme.primary
        "onPrimary" -> scheme.onPrimary
        "primaryContainer" -> scheme.primaryContainer
        "onPrimaryContainer" -> scheme.onPrimaryContainer
        "inversePrimary" -> scheme.inversePrimary
        "secondary" -> scheme.secondary
        "onSecondary" -> scheme.onSecondary
        "secondaryContainer" -> scheme.secondaryContainer
        "onSecondaryContainer" -> scheme.onSecondaryContainer
        "tertiary" -> scheme.tertiary
        "onTertiary" -> scheme.onTertiary
        "tertiaryContainer" -> scheme.tertiaryContainer
        "onTertiaryContainer" -> scheme.onTertiaryContainer
        "background" -> scheme.background
        "onBackground" -> scheme.onBackground
        "surface" -> scheme.surface
        "onSurface" -> scheme.onSurface
        "surfaceVariant" -> scheme.surfaceVariant
        "onSurfaceVariant" -> scheme.onSurfaceVariant
        "surfaceTint" -> scheme.surfaceTint
        "inverseSurface" -> scheme.inverseSurface
        "inverseOnSurface" -> scheme.inverseOnSurface
        "border" -> scheme.border
        "borderVariant" -> scheme.borderVariant
        "error" -> scheme.error
        "onError" -> scheme.onError
        "errorContainer" -> scheme.errorContainer
        "onErrorContainer" -> scheme.onErrorContainer
        "scrim" -> scheme.scrim
        else -> error("no ColorScheme property for role '$tvRole'")
    }

    @Test
    fun everyMappedRoleCarriesThePaletteValueItClaimsTo() {
        for ((tvRole, paletteRole) in tvRoleToPaletteRole) {
            assertEquals(
                "TV role '$tvRole' should carry CrispyPalette.$paletteRole",
                paletteValue(paletteRole),
                schemeValue(tvRole),
            )
        }
    }

    @Test
    fun noMappedRoleCoincidesWithTheTvLibrarysOwnDefault() {
        // The non-vacuity gate for the case above, and the reason it exists: if a
        // palette value equalled the library default for its role, the per-role
        // assertion would pass whether or not the role was passed.
        //
        // `scrim` is the one role that collides, and it is a real limit rather than a
        // mistake. `CrispyPalette.scrim` is `0xFF000000` and
        // `androidx.tv.material3`'s own default for `scrim` is also opaque black, so
        // the two are indistinguishable and **no assertion can tell a mapped `scrim`
        // from a dropped one**. The exclusion is asserted as exactly this one role, so
        // a second collision fails rather than being absorbed into the list — which is
        // the difference between documenting a limit and ignoring one.
        val indistinguishable = tvRoleToPaletteRole.keys.filter {
            paletteValue(tvRoleToPaletteRole.getValue(it)) == schemeDefault(it)
        }
        assertEquals(
            "a TV role's palette value now equals the library default, so its assertion " +
                "cannot tell a mapped role from a dropped one. If this is genuinely " +
                "unavoidable, name the role here; otherwise re-measure the mapping.",
            setOf("scrim"),
            indistinguishable.toSet(),
        )
    }

    @Test
    fun theBorderRolesAreTheOutlinesUnderTheOtherLibrarysNames() {
        // The single most valuable pair in the file, stated on its own so a failure
        // points at the cross-library mapping rather than at 29 assertions.
        assertEquals(CrispyPalette.outline, scheme.border)
        assertEquals(CrispyPalette.outlineVariant, scheme.borderVariant)
    }

    @Test
    fun theTwoBorderRolesAreNotInterchangeable() {
        // The pair is only testable because the two values differ. If a future palette
        // change collapsed them, the two assertions above would keep passing while
        // proving nothing, and this is the case that says so.
        assertEquals(Color(0xFF333333), CrispyPalette.outline)
        assertEquals(Color(0xFF262626), CrispyPalette.outlineVariant)
    }

    @Test
    fun noOtherTvRoleIsWiredToAnOutline() {
        // The two outlines must reach the scheme *only* through `border` and
        // `borderVariant`. Compared as a key set so a new holder names itself.
        val outlineHolders = tvRoleToPaletteRole.filterValues {
            it == "outline" || it == "outlineVariant"
        }
        assertEquals(
            "only the two border roles may be wired to an outline",
            setOf("border", "borderVariant"),
            outlineHolders.keys,
        )
    }

    @Test
    fun twentySevenOfTheTwentyNineMappingsAreTheIdentity() {
        // Stated rather than assumed: the mapping table is 29 entries, 27 of which map
        // a role to itself. If a *new* rename is introduced, this fails and forces the
        // count to be re-examined — which is the point, because the two renames are
        // the only place a reader has to look for a cross-library translation.
        // Compared as a set of pairs: `Map.filter` yields a `Map`, and a `Map`'s
        // iteration order is not something an assertion should be coupled to.
        assertEquals(
            setOf("border" to "outline", "borderVariant" to "outlineVariant"),
            tvRoleToPaletteRole.filter { (tv, palette) -> tv != palette }
                .map { (tv, palette) -> tv to palette }
                .toSet(),
        )
    }

    @Test
    fun thePaletteRolesWithNoTvSlotAreTheEightThisSaysTheyAre() {
        // The KDoc used to say seven, under-counting by one: `spinner` is not a
        // Material3 role and is also unmapped. Enumerating the *unmapped* set is the
        // half a reader cannot check from `Theme.kt` — the passed roles are all visible
        // there, the absent ones are not. Derived by name, never by value.
        assertEquals(
            setOf(
                "spinner",
                "surfaceContainer",
                "surfaceContainerHigh",
                "surfaceContainerHighest",
                "surfaceContainerLow",
                "surfaceContainerLowest",
                "surfaceDim",
                "surfaceBright",
            ),
            paletteRoles - tvRoleToPaletteRole.values.toSet(),
        )
    }

    @Test
    fun theMappedAndUnmappedRolesPartitionTheWholePalette() {
        // 29 + 8 = 37. Without this the two enumerations could both be right while a
        // role were counted twice or dropped, and the counts are the only cheap check.
        assertEquals(37, tvRoleToPaletteRole.size + (paletteRoles - tvRoleToPaletteRole.values.toSet()).size)
        assertEquals(paletteRoles.size, tvRoleToPaletteRole.size + (paletteRoles - tvRoleToPaletteRole.values.toSet()).size)
    }

    @Test
    fun thePaletteHasNoRoleThisSuiteHasNeverHeardOf() {
        // `paletteRoles` is a hand-written set, so a role added to `CrispyPalette` would
        // make the partition above over-count rather than fail. This closes that: the
        // expected total is the measured one, and the count is stated in the message.
        assertEquals("CrispyPalette's role count changed; update paletteRoles and the two " +
            "unmapped assertions", 37, paletteRoles.size)
    }

    @Test
    fun theTenRolesThatShareWhiteAreNamedRatherThanAssumed() {
        // The limit of this suite, stated as a test rather than a comment so the day
        // the palette changes it fails and the claim is re-measured. Ten roles carry
        // `0xFFFFFFFF`; a swap among *them* is invisible to every other case here,
        // which is why this enumerates them by name instead of counting.
        // As a set, not a list: `paletteRoles.filter { … }` returns a list in the
        // set's iteration order, and coupling an assertion to that order makes it fail
        // for a reason that has nothing to do with the palette.
        assertEquals(
            setOf(
                "primary",
                "onPrimaryContainer",
                "inversePrimary",
                "onSecondaryContainer",
                "onTertiary",
                "onTertiaryContainer",
                "onBackground",
                "onSurface",
                "onError",
                "surfaceTint",
            ),
            paletteRoles.filter { paletteValue(it) == WHITE }.toSet(),
        )
    }

    private fun schemeDefault(tvRole: String): Color = when (tvRole) {
        "primary" -> tvDefaults.primary
        "onPrimary" -> tvDefaults.onPrimary
        "primaryContainer" -> tvDefaults.primaryContainer
        "onPrimaryContainer" -> tvDefaults.onPrimaryContainer
        "inversePrimary" -> tvDefaults.inversePrimary
        "secondary" -> tvDefaults.secondary
        "onSecondary" -> tvDefaults.onSecondary
        "secondaryContainer" -> tvDefaults.secondaryContainer
        "onSecondaryContainer" -> tvDefaults.onSecondaryContainer
        "tertiary" -> tvDefaults.tertiary
        "onTertiary" -> tvDefaults.onTertiary
        "tertiaryContainer" -> tvDefaults.tertiaryContainer
        "onTertiaryContainer" -> tvDefaults.onTertiaryContainer
        "background" -> tvDefaults.background
        "onBackground" -> tvDefaults.onBackground
        "surface" -> tvDefaults.surface
        "onSurface" -> tvDefaults.onSurface
        "surfaceVariant" -> tvDefaults.surfaceVariant
        "onSurfaceVariant" -> tvDefaults.onSurfaceVariant
        "surfaceTint" -> tvDefaults.surfaceTint
        "inverseSurface" -> tvDefaults.inverseSurface
        "inverseOnSurface" -> tvDefaults.inverseOnSurface
        "border" -> tvDefaults.border
        "borderVariant" -> tvDefaults.borderVariant
        "error" -> tvDefaults.error
        "onError" -> tvDefaults.onError
        "errorContainer" -> tvDefaults.errorContainer
        "onErrorContainer" -> tvDefaults.onErrorContainer
        "scrim" -> tvDefaults.scrim
        else -> error("no ColorScheme default for role '$tvRole'")
    }
}
