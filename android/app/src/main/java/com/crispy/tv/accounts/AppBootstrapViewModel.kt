package com.crispy.tv.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for app-level bootstrap state.
 *
 * The app renders one `AppRoot` subtree whose content is driven by [state]. Auth and Onboarding
 * screens never call `navController.navigate(Home)`; they call [refresh] and the gate flips here,
 * so the navbar host (`AppRoot`'s `MainAppShell`) is mounted/unmounted cleanly.
 */
sealed interface BootstrapState {
    data object Loading : BootstrapState
    data object NeedsAuth : BootstrapState
    data object NeedsProfileSelection : BootstrapState
    data object Ready : BootstrapState
}

class AppBootstrapViewModel internal constructor(
    private val bootstrapRepository: AccountBootstrapRepository,
) : ViewModel() {
    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AppBootstrapViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AppBootstrapViewModel(
                            bootstrapRepository = SupabaseServicesProvider.bootstrapRepository(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.Loading)
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-run bootstrap and flip [state] accordingly. Safe to call from Auth (after sign-in)
     * and Onboarding (after profile creation) screens; also used after sign-out.
     */
    fun refresh() {
        _state.value = BootstrapState.Loading
        viewModelScope.launch {
            val result = runCatching { bootstrapRepository.bootstrap() }.getOrNull()
            _state.value = when {
                result == null || !result.signedIn -> BootstrapState.NeedsAuth
                !result.onboardingComplete -> BootstrapState.NeedsProfileSelection
                else -> BootstrapState.Ready
            }
        }
    }

    /**
     * Called by [AccountSettingsViewModel] after a successful account deletion / sign-out
     * so the root gate flips back to Auth without races.
     */
    fun onSignedOut() {
        viewModelScope.launch { runCatching { bootstrapRepository.signOut() } }
        refresh()
    }
}
