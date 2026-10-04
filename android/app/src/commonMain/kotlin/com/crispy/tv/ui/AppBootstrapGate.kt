package com.crispy.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.accounts.AppBootstrapViewModel
import com.crispy.tv.accounts.AuthRoute
import com.crispy.tv.accounts.BootstrapState
import com.crispy.tv.accounts.ProfileSelectorRoute
import com.crispy.tv.ui.brand.CrispyIntroSplash
import kotlinx.coroutines.delay

private const val IntroTimeoutMs = 3_000L

/**
 * Intro, then auth, then profile selection, then [ready] -- the part of the app
 * that runs before there is a session, a profile or a shell.
 *
 * ## What was extracted, and why this is the cut
 *
 * [AppRoot] is this gate plus `MainAppShell`, and `MainAppShell` is still
 * `androidMain`-shaped even though its file is `commonMain`: it builds a
 * `NavController` over an `AppNavHost` whose ~40-member dependency bundle
 * cannot be constructed off Android (three `(NavBackStackEntry) -> …` argument
 * readers return an `android.os.Bundle` on *every* target, `addPlayerDestination`
 * is `PlayerNavGraph.kt`, the hero trailer layer is Media3, and the provider-logo
 * badges read the plain-library `:ui-assets`).
 *
 * So the seam is drawn at the last portable screen rather than at the shell:
 * everything a platform can share runs here, and the caller supplies the content
 * for [BootstrapState.Ready]. That is a `@Composable (onSignedOut: () -> Unit) -> Unit`
 * rather than a bundle or a `T` for the reason the rest of the app's seams have
 * the shape they do -- what separates the three slot shapes is not "is this a
 * lambda" but **who has to be a composable for it to work**. Here the answer is
 * the caller: rendering the shell *is* the job.
 *
 * ## What the gate does not decide
 *
 * The three factories are passed in rather than built, so this file reads no
 * platform type and constructs no service: `AndroidAppRoot` builds them off the
 * `Application`-owned graph, and `DesktopAppRoot` builds them off the graph its
 * own entry point constructed. `onSignedOut` is handed *to* [ready] rather than
 * called here because every ready branch wants it -- the shell routes it into
 * `AppNavHost`, and the desktop's ready content has no use for it yet, which is
 * the honest shape of a platform that has not built its shell.
 */
@Composable
fun AppBootstrapGate(
    bootstrapViewModelFactory: ViewModelProvider.Factory,
    authViewModelFactory: ViewModelProvider.Factory,
    profileListViewModelFactory: ViewModelProvider.Factory,
    ready: @Composable (onSignedOut: () -> Unit) -> Unit,
) {
    val bootstrapViewModel: AppBootstrapViewModel = viewModel(factory = bootstrapViewModelFactory)
    val state by bootstrapViewModel.state.collectAsStateWithLifecycle()
    var introDone by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(IntroTimeoutMs)
        introDone = true
    }

    // Hold the splash until the intro has played through even if bootstrap
    // resolves first; afterwards the finished frame holds until data arrives.
    when {
        state == BootstrapState.Loading || !introDone -> {
            CrispyIntroSplash(
                playIntro = !introDone,
                onFinished = { introDone = true },
            )
        }
        state == BootstrapState.NeedsAuth -> {
            AuthRoute(
                onSignedIn = { bootstrapViewModel.refresh() },
                viewModelFactory = authViewModelFactory,
            )
        }
        state == BootstrapState.NeedsProfileSelection -> {
            ProfileSelectorRoute(
                onComplete = { bootstrapViewModel.refresh() },
                onBack = { bootstrapViewModel.onSignedOut() },
                viewModelFactory = profileListViewModelFactory,
            )
        }
        state == BootstrapState.Ready -> {
            // Positional, not named: Kotlin prohibits named arguments on a value of a
            // function type, so the parameter name is documentation only here.
            ready { bootstrapViewModel.onSignedOut() }
        }
    }
}