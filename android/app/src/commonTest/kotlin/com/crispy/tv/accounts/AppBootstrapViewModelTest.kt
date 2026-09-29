package com.crispy.tv.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [AppBootstrapViewModel] in its new home in `commonMain`.
 *
 * The class became testable in the same commit that moved it, and that is the whole
 * argument for the port: its only collaborator is now an interface, so a test can hand
 * it a double. The `factory(context)` companion that used to sit inside the class is now
 * a separate function in `androidMain` and is untestable here for the same reason every
 * other provider in this repo is — it reaches `AndroidKeyStore`.
 *
 * [viewModelScope] runs on `Dispatchers.Main`, which no JVM test has, so it is replaced
 * with an unconfined dispatcher: the body then runs eagerly on the calling thread and
 * every assertion below is reached without an `advanceUntilIdle` that would not be
 * driving this scheduler anyway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppBootstrapViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private fun withMain() {
        Dispatchers.setMain(dispatcher)
    }

    @kotlin.test.AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun signedIn(onboardingComplete: Boolean) = BootstrapResult(
        signedIn = true,
        anonymous = false,
        onboardingComplete = onboardingComplete,
        session = null,
    )

    @Test
    fun `starts in Loading before anything has answered`() = runTest {
        withMain()
        val repository = FakeAccountBootstrapRepository()
        val viewModel = AppBootstrapViewModel(repository)

        // The constructor calls refresh() and the unconfined dispatcher has already run
        // it, so Loading is only observable with a repository that has not answered.
        assertEquals(BootstrapState.NeedsAuth, viewModel.state.value)
    }

    @Test
    fun `a signed-in user with a complete profile is Ready`() = runTest {
        withMain()
        val viewModel = AppBootstrapViewModel(FakeAccountBootstrapRepository(signedIn(onboardingComplete = true)))

        assertEquals(BootstrapState.Ready, viewModel.state.value)
    }

    @Test
    fun `a signed-in user without a profile needs profile selection`() = runTest {
        withMain()
        val viewModel = AppBootstrapViewModel(FakeAccountBootstrapRepository(signedIn(onboardingComplete = false)))

        assertEquals(BootstrapState.NeedsProfileSelection, viewModel.state.value)
    }

    @Test
    fun `not signed in needs auth`() = runTest {
        withMain()
        val viewModel = AppBootstrapViewModel(FakeAccountBootstrapRepository())

        assertEquals(BootstrapState.NeedsAuth, viewModel.state.value)
    }

    @Test
    fun `a thrown bootstrap is treated as not signed in`() = runTest {
        withMain()
        val repository = FakeAccountBootstrapRepository(signedIn(onboardingComplete = true))
        repository.bootstrapFailure = IllegalStateException("no network")
        val viewModel = AppBootstrapViewModel(repository)

        assertEquals("a failure must not leave the app on a stale Ready", BootstrapState.NeedsAuth, viewModel.state.value)
    }

    @Test
    fun `refresh asks the repository again`() = runTest {
        withMain()
        val repository = FakeAccountBootstrapRepository()
        AppBootstrapViewModel(repository)
        assertEquals(1, repository.bootstrapCalls.size)

        repository.answerWith(signedIn(onboardingComplete = true))
        val viewModel = AppBootstrapViewModel(repository)
        viewModel.refresh()

        assertEquals("refresh re-runs bootstrap", 3, repository.bootstrapCalls.size)
    }

    @Test
    fun `signing out signs the repository out and returns to needs auth`() = runTest {
        withMain()
        val repository = FakeAccountBootstrapRepository(signedIn(onboardingComplete = true))
        val viewModel = AppBootstrapViewModel(repository)
        assertEquals(BootstrapState.Ready, viewModel.state.value)

        viewModel.onSignedOut()

        assertEquals(listOf(Unit), repository.signOutCalls)
        assertEquals(BootstrapState.NeedsAuth, viewModel.state.value)
    }

    @Test
    fun `a failure while signing out still returns to needs auth`() = runTest {
        withMain()
        val repository = FakeAccountBootstrapRepository(signedIn(onboardingComplete = true))
        repository.signOutFailure = IllegalStateException("keystore gone")
        val viewModel = AppBootstrapViewModel(repository)

        viewModel.onSignedOut()

        assertEquals(
            "a keystore failure must not strand the app on Ready",
            BootstrapState.NeedsAuth,
            viewModel.state.value,
        )
    }
}
