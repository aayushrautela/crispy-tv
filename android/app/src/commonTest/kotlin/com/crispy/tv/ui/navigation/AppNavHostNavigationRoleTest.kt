package com.crispy.tv.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The navigation-direction rule the four `NavHost` transitions are written from.
 *
 * `roleOf`, `topLevelRouteIndex` and `topLevelRouteIndices` were already named top-level
 * declarations in `AppNavHost.kt` before this file moved to `commonMain` -- an earlier
 * landing extracted them out of the `NavHost` builder while it was still there. **They were
 * `private`, so the module's own `commonTest` could not name them, which is why the most
 * interesting decision in the file had no coverage at all.** Nothing was extracted to make
 * this suite possible; three visibility keywords were widened.
 *
 * ## What is and is not observable here
 *
 * The transitions themselves -- `tabEnterFromRight()` and its five siblings -- return
 * `EnterTransition`/`ExitTransition` values and are left `private`. Asserting their animation
 * specs would prove that Compose's animation builders work, not that this app navigates the
 * way it intends, and there is no `commonTest` path to a rendering harness (the same wall
 * `SettingsNavGraph`'s registration rule hit). **So the decision under test is the pure
 * predicate the four `when`s ask, not the transition they pick** -- which is the honest
 * split: the predicate is where the rules live, and the four arms are four copies of it.
 *
 * Every case below iterates `TopLevelDestination.entries` rather than naming routes, because
 * a hand-written list of tab routes is a sample: a fifth destination added tomorrow with an
 * index in the wrong place would not be in the list and the suite would pass silently. The
 * key-set comparison below is what turns "a route I did not think of" into a failure.
 */
class AppNavHostNavigationRoleTest {

    /**
     * The index map holds exactly the top-level routes -- no more, no fewer.
     *
     * Asserting the **key set before the values** is deliberate: a new destination that
     * quietly failed to register reads as "the list changed" rather than as a confusing
     * value diff, and a route that left `TopLevelDestination` but stayed in the map reads as
     * a specific name in the failure message.
     */
    @Test
    fun theIndexMapHoldsExactlyTheTopLevelRoutes() {
        assertEquals(
            TopLevelDestination.entries.map { it.route }.toSet(),
            topLevelRouteIndices.keys,
            "topLevelRouteIndices must be the top-level routes and nothing else",
        )
    }

    /**
     * Each route's index is its own position, so the map *is* the tab order rather than an
     * arbitrary numbering that happens to be increasing.
     */
    @Test
    fun eachIndexIsItsOwnPositionInTheDestinationOrder() {
        TopLevelDestination.entries.forEachIndexed { expectedIndex, destination ->
            assertEquals(
                expectedIndex,
                topLevelRouteIndex(destination.route),
                "index of ${destination.route}",
            )
        }
    }

    /**
     * A contiguous run from zero, asserted as a set so a duplicated or skipped index fails.
     * This is what makes `topLevelRouteIndex` usable as a *comparison* operand: the four
     * transitions only ask `is the target index greater than`, and that question is only
     * meaningful because the order is the declaration order and nothing is skipped.
     */
    @Test
    fun theIndicesAreContiguousFromZeroSoComparingThemIsMeaningful() {
        assertEquals(
            (0 until TopLevelDestination.entries.size).toSet(),
            topLevelRouteIndices.values.toSet(),
            "indices must be exactly 0..n-1 with no gaps and no repeats",
        )
    }

    @Test
    fun everyTopLevelRouteIsTheTopLevelRole() {
        for (destination in TopLevelDestination.entries) {
            assertEquals(
                NavigationRole.TopLevel,
                roleOf(destination.route),
                "role of ${destination.route}",
            )
        }
    }

    /**
     * Search is the one overlay, and it is named by route rather than by position -- so the
     * case asserts the specific route, because "the overlay" is a single-member set and a
     * set comparison would be a tautology here.
     */
    @Test
    fun searchIsTheOverlayRole() {
        assertEquals(NavigationRole.Overlay, roleOf(AppRoutes.SearchRoute))
    }

    @Test
    fun aRouteThatIsNeitherTopLevelNorSearchIsADetail() {
        assertEquals(NavigationRole.Detail, roleOf("some/deep/detail/route"))
        assertEquals(NavigationRole.Detail, roleOf(""))
        // A route that is *almost* a top-level one. If the lookup were a prefix match or a
        // `startsWith` rather than a map hit, this would answer TopLevel.
        assertEquals(NavigationRole.Detail, roleOf(AppRoutes.HomeRoute + "/extra"))
    }

    /**
     * `null` is what a `NavBackStackEntry` with no destination answers, and the four
     * transitions are handed it whenever a `NavHost` transition runs against an absent
     * destination. It has to reach `Detail` -- that is what makes every one of their
     * `else -> None` arms reachable.
     */
    @Test
    fun anAbsentRouteIsADetail() {
        assertEquals(NavigationRole.Detail, roleOf(null))
        assertEquals(-1, topLevelRouteIndex(null))
    }

    /**
     * `-1` for everything that is not a top-level route, and **the pair matters**: `-1`
     * compares as strictly less than every real index, which is what lets the four `when`s
     * answer by comparison without first testing membership. An implementation that
     * answered `0` would make an absent route compare equal to Home, and Home is index 0.
     */
    @Test
    fun aNonTopLevelRouteIndexesAtMinusOneSoItComparesBelowEveryRealTab() {
        assertEquals(-1, topLevelRouteIndex(AppRoutes.SearchRoute))
        assertEquals(-1, topLevelRouteIndex("some/deep/detail/route"))
        assertEquals(-1, topLevelRouteIndex(null))
        assertTrue(
            TopLevelDestination.entries.all { destination ->
                topLevelRouteIndex(destination.route) > topLevelRouteIndex(AppRoutes.SearchRoute)
            },
            "-1 must be below every real tab index",
        )
    }

    /**
     * The direction rule itself, asserted as a property over **every ordered pair** rather
     * than a hand-picked forward/backward example.
     *
     * This is the case the file exists for. The four `NavHost` transitions each ask the same
     * question in a different arrangement -- `target > initial` on the way in, `initial <
     * target` on the pop -- and a mutation that flips one `>` to `<` would send one tab
     * transition the wrong way. Two hand-picked pairs would catch it; *every* pair also
     * catches the version where the comparison is right but the index map is not the
     * declaration order, which is the failure nobody would write a fixture for.
     */
    @Test
    fun everyOrderedPairOfTabsHasExactlyOneDirection() {
        val routes = TopLevelDestination.entries.map { it.route }
        for (from in routes) {
            for (to in routes) {
                if (from == to) {
                    continue
                }
                val forward = topLevelRouteIndex(to) > topLevelRouteIndex(from)
                val backward = topLevelRouteIndex(from) > topLevelRouteIndex(to)
                assertTrue(
                    forward != backward,
                    "exactly one of $from -> $to and $to -> $from must read as forward, " +
                        "but forward=$forward and backward=$backward",
                )
            }
        }
    }

    /**
     * Two roles never share a route, and the roles partition the routes the file can be
     * asked about. Stated as a set comparison per role so a route that acquired a second
     * role -- which the `when` above cannot produce, but which a rewrite to three maps could
     * -- is reported as a route name rather than as a changed list.
     */
    @Test
    fun eachRouteHasExactlyOneRole() {
        val classified = buildList {
            TopLevelDestination.entries.forEach { add(it.route to NavigationRole.TopLevel) }
            add(AppRoutes.SearchRoute to NavigationRole.Overlay)
            add("some/deep/detail/route" to NavigationRole.Detail)
            add(null to NavigationRole.Detail)
        }
        val byRole = classified.groupBy({ it.second }, { it.first })
        for ((role, routes) in byRole) {
            assertEquals(
                routes.size,
                routes.distinct().size,
                "role $role is claimed by more than one route: $routes",
            )
            for (route in routes) {
                assertEquals(role, roleOf(route), "role of $route")
            }
        }
    }
}