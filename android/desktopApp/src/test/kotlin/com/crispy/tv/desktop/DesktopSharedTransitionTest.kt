package com.crispy.tv.desktop

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi

import androidx.compose.ui.test.v2.runComposeUiTest
import com.crispy.tv.ui.navigation.CrispySharedTransitionLayout
import com.crispy.tv.ui.navigation.LocalSharedTransitionScope
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Proves the shared-transition host works on a target that is not Android.
 *
 * ## What is actually being claimed
 *
 * `:app` has 14 files in `commonMain` that participate in a shared element
 * transition, and they all read the same composition local,
 * `LocalSharedTransitionScope`. Until this landing that local was supplied from
 * `AppNavHost.kt`, which is `androidMain` -- so on desktop every one of those 14
 * participants read `null` and rendered without a transition, silently, and with
 * no error anywhere.
 *
 * The fix is `CrispySharedTransitionLayout` in `commonMain`, and this is the test
 * that says the desktop host is now wired to it.
 *
 * ## Why there are two cases
 *
 * The first case alone is a tautology risk: a composition local that always had a
 * value would pass it too. The second case is what makes the first meaningful --
 * it pins that the value comes from the new host and not from the local's own
 * default, and it simultaneously pins the default itself, which is what the 14
 * participants rely on for their "no transition" path.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopSharedTransitionTest {

    @Test
    fun theSharedTransitionHostSuppliesAScopeOnDesktop() {
        val recorder = ScopeRecorder()

        runComposeUiTest {
            setContent {
                CrispySharedTransitionLayout {
                    RecordScope(recorder)
                }
            }
        }

        assertNotNull(
            recorder.scope,
            "CrispySharedTransitionLayout must supply a scope, or every commonMain " +
                "participant renders with no transition on desktop and nothing says so",
        )
    }

    @Test
    fun nothingSuppliesAScopeWithoutTheHost() {
        val recorder = ScopeRecorder()

        runComposeUiTest {
            setContent {
                RecordScope(recorder)
            }
        }

        assertNull(
            recorder.scope,
            "LocalSharedTransitionScope defaults to null and nothing else provides it; " +
                "if this ever fails, the case above is passing for the wrong reason",
        )
    }
}

/**
 * Reads the composition local during composition and leaves it here.
 *
 * A plain field rather than snapshot state on purpose: the value is written once
 * and read once, after `setContent` has run. Snapshot state would make the write
 * itself a recomposition trigger, which is machinery this test has no use for.
 */
private class ScopeRecorder {
    var scope: SharedTransitionScope? = null
}

@Composable
private fun RecordScope(recorder: ScopeRecorder) {
    recorder.scope = LocalSharedTransitionScope.current
}
