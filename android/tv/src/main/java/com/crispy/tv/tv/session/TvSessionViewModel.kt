package com.crispy.tv.tv.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.accounts.Session
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.tv.di.TvServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TvSessionState {
    data object Loading : TvSessionState
    data class SignedOut(val configError: Boolean = false) : TvSessionState
    data class NeedsProfile(val profiles: List<CrispyBackendClient.Profile>) : TvSessionState
    data class SignedIn(val context: BackendContext) : TvSessionState
}

class TvSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<TvSessionState>(TvSessionState.Loading)
    val state: StateFlow<TvSessionState> = _state.asStateFlow()

    val signInInFlight = MutableStateFlow(false)
    val signInError = MutableStateFlow<String?>(null)

    private var pendingSession: Session? = null

    init {
        restoreSession()
    }

    fun restoreSession() {
        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val accountClient = TvServices.accountClient(appContext)
            if (!accountClient.isConfigured()) {
                _state.value = TvSessionState.SignedOut(configError = true)
                return@launch
            }
            val session = runCatching { accountClient.ensureValidSession() }.getOrNull()
            if (session == null) {
                _state.value = TvSessionState.SignedOut()
                return@launch
            }
            proceedWithSession(session)
        }
    }

    fun signIn(email: String, password: String) {
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            signInInFlight.value = true
            signInError.value = null
            try {
                val session = TvServices.accountClient(appContext).signInWithEmail(
                    email = email.trim(),
                    password = password,
                )
                proceedWithSession(session)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                signInError.value = t.message ?: "Sign-in failed"
            } finally {
                signInInFlight.value = false
            }
        }
    }

    fun selectProfile(profileId: String) {
        val appContext = getApplication<Application>()
        val session = pendingSession ?: return
        TvServices.activeProfileStore(appContext).setActiveProfileId(session.userId, profileId)
        viewModelScope.launch {
            val resolved = TvServices.contextResolver(appContext).resolve()
            _state.value = resolved?.let { TvSessionState.SignedIn(it) }
                ?: TvSessionState.NeedsProfile(currentProfiles())
        }
    }

    fun signOut() {
        val appContext = getApplication<Application>()
        viewModelScope.launch {
            TvServices.signOut(appContext)
            pendingSession = null
            _state.value = TvSessionState.SignedOut()
        }
    }

    private suspend fun proceedWithSession(session: Session) {
        val appContext = getApplication<Application>()
        pendingSession = session
        val resolver = TvServices.contextResolver(appContext)
        val resolved = resolver.resolve()
        if (resolved != null) {
            _state.value = TvSessionState.SignedIn(resolved)
            return
        }
        _state.value = TvSessionState.NeedsProfile(currentProfiles())
    }

    private suspend fun currentProfiles(): List<CrispyBackendClient.Profile> {
        val appContext = getApplication<Application>()
        val session = pendingSession ?: return emptyList()
        return runCatching {
            TvServices.backendClient(appContext).listProfiles(session.accessToken)
        }.getOrDefault(emptyList())
    }
}
