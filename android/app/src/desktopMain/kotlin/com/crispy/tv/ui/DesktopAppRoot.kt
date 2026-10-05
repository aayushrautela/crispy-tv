package com.crispy.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.crispy.tv.accounts.appBootstrapViewModelFactory
import com.crispy.tv.accounts.authViewModelFactory
import com.crispy.tv.accounts.profileListViewModelFactory
import com.crispy.tv.app.AppGraph

/**
 * The desktop entry point: the shared bootstrap gate, with the desktop's own ready content.
 *
 * ## Why the graph is a parameter
 *
 * `AndroidAppRoot` takes nothing and reads the graph off the `Application`, because
 * on Android two other components need it too -- the application starts the
 * mutation outbox and an activity clears the pending provider auth on resume. The
 * desktop entry point is the only holder, so `Main.kt` builds one graph and passes
 * it here rather than this file building a second one behind its caller's back: a
 * second `AppGraph` over the same `AppServices` is survivable, a second
 * `AppServices` is a second token store, a second snapshot cache and a second
 * service scope.
 *
 * ## What this renders, and what it does not
 *
 * The gate is the whole shared prefix -- splash, auth, profile selection -- and it
 * is real shared code rather than a desktop copy. The `ready` slot is where the
 * desktop shell would go, and it is passed in because there is nothing to put
 * there yet: `MainAppShell` builds an `AppNavHost`, whose 40-member dependency bundle
 * cannot be constructed off Android (the player destination is `PlayerNavGraph.kt`,
 * the hero trailer layer is Media3, the provider-logo badges read the plain-library
 * `:ui-assets`). Until that bundle can be built, the caller supplies its own ready
 * branch and this function stays a three-`remember` mirror of [AndroidAppRoot].
 *
 * An unconfigured desktop build lands on [com.crispy.tv.accounts.BootstrapState.NeedsAuth],
 * not on a blank frame: with a blank Supabase URL the bootstrap repository answers
 * `signedIn = false` rather than failing, so the auth screen is the first thing a
 * developer running `./gradlew :android:desktopApp:run` sees.
 */
@Composable
fun DesktopAppRoot(
    graph: AppGraph,
    ready: @Composable (onSignedOut: () -> Unit) -> Unit,
) {
    AppBootstrapGate(
        bootstrapViewModelFactory = remember(graph) { appBootstrapViewModelFactory(graph) },
        authViewModelFactory = remember(graph) { authViewModelFactory(graph) },
        profileListViewModelFactory = remember(graph) { profileListViewModelFactory(graph) },
        ready = ready,
    )
}