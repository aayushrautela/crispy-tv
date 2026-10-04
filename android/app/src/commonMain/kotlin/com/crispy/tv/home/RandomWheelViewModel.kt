package com.crispy.tv.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.domain.home.HomeRandomCandidate
import com.crispy.tv.platform.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The random-pick wheel's own state.
 *
 * It exists for one reason: [HomeCatalogService.loadRandomCandidates] reaches the backend on its
 * first call in a session (its in-flight deduplication only collapses *concurrent* loads), so the
 * answer has to survive the destination being left and re-entered. Holding it in the composable
 * would reload on every push of the route, and holding it in [HomeViewModel] would not reach the
 * wheel at all, because that view model is scoped to the home back-stack entry and the wheel is a
 * different destination.
 *
 * **The chip selection is deliberately not here.** It is presentation-local: it must reset when the
 * candidates change (a home refresh can drop a genre out from under a selected chip) and it is
 * rebuilt from [candidates] in the composable anyway, so two owners would be two sources of truth
 * for one value.
 *
 * [HomeCatalogService] rather than [HomeRefreshCoordinator], for the reason
 * [CatalogViewModel]'s KDoc gives for its own identical slot: the coordinator is refresh
 * orchestration, and this call is a read.
 */
class RandomWheelViewModel internal constructor(
    private val homeCatalogService: HomeCatalogService,
    private val ioDispatcher: CoroutineDispatcher,
    private val logger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(RandomWheelUiState())
    val state: StateFlow<RandomWheelUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** Loads once; a later call finds the answer already in [_state] and does nothing. */
    fun loadIfMissing() {
        if (_state.value.loaded) return
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val candidates = withContext(ioDispatcher) {
            try {
                homeCatalogService.loadRandomCandidates()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Empty rather than thrown, and empty rather than an error event: the wheel has
                // its own empty state, and a snackbar behind a full-screen destination would not
                // be seen. An empty answer is still memoised, so a later entry retries rather
                // than hammering the backend on every recomposition.
                logger.warn(TAG, "Random candidate load failed", error)
                emptyList()
            }
        }
        _state.value = RandomWheelUiState(isLoading = false, loaded = true, candidates = candidates)
    }

    private companion object {
        const val TAG = "RandomWheel"
    }
}

/**
 * Whether the wheel may draw yet, and what it draws.
 *
 * [loaded] is separate from [isLoading] because the two are different facts: the first composition
 * has loaded nothing (neither an answer nor a failure), while an empty answer after a load means
 * there is genuinely nothing to spin. Collapsing them would show "Nothing to spin from yet." for
 * the frame before the request returns.
 */
data class RandomWheelUiState(
    val isLoading: Boolean = true,
    val loaded: Boolean = false,
    val candidates: List<HomeRandomCandidate> = emptyList(),
)
