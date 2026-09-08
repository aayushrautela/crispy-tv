package com.crispy.tv.tv.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.accounts.Session
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.network.AppHttp
import com.crispy.tv.tv.BuildConfig
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

sealed interface DeviceLoginState {
    data object Idle : DeviceLoginState
    data object Requesting : DeviceLoginState
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresAtMs: Long,
    ) : DeviceLoginState
    data class Failed(val message: String) : DeviceLoginState
    data object Denied : DeviceLoginState
    data object Expired : DeviceLoginState
}

class TvSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<TvSessionState>(TvSessionState.Loading)
    val state: StateFlow<TvSessionState> = _state.asStateFlow()

    val signInInFlight = MutableStateFlow(false)
    val signInError = MutableStateFlow<String?>(null)

    private val _deviceLoginState = MutableStateFlow<DeviceLoginState>(DeviceLoginState.Idle)
    val deviceLoginState: StateFlow<DeviceLoginState> = _deviceLoginState.asStateFlow()

    private var pendingSession: Session? = null
    private var deviceLoginJob: kotlinx.coroutines.Job? = null

    val isDeviceLoginAvailable: Boolean = runCatching {
        TvServices.backendClient(app).isConfigured()
    }.getOrDefault(false)

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

    fun startDeviceLogin() {
        if (deviceLoginJob?.isActive == true) return
        val appContext = getApplication<Application>()
        val client = deviceLoginClient(appContext) ?: run {
            _deviceLoginState.value = DeviceLoginState.Failed("Backend URL is not configured.")
            return
        }
        deviceLoginJob = viewModelScope.launch {
            _deviceLoginState.value = DeviceLoginState.Requesting
            try {
                val authorization = client.authorize()
                _deviceLoginState.value = DeviceLoginState.AwaitingApproval(
                    userCode = formatUserCode(authorization.userCode),
                    verificationUri = authorization.verificationUri,
                    verificationUriComplete = authorization.verificationUriComplete,
                    expiresAtMs = System.currentTimeMillis() + authorization.expiresInSec * 1000L,
                )
                pollForApproval(appContext, client, authorization.deviceCode, authorization.intervalSec)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _deviceLoginState.value = DeviceLoginState.Failed(t.message ?: "Device sign-in failed")
            }
        }
    }

    fun retryDeviceLogin() {
        cancelDeviceLogin()
        startDeviceLogin()
    }

    fun cancelDeviceLogin() {
        deviceLoginJob?.cancel()
        deviceLoginJob = null
        _deviceLoginState.value = DeviceLoginState.Idle
    }

    private suspend fun pollForApproval(
        appContext: Application,
        client: DeviceLoginClient,
        deviceCode: String,
        initialIntervalSec: Long,
    ) {
        var intervalSec = initialIntervalSec
        while (true) {
            kotlinx.coroutines.delay(intervalSec * 1000L)
            val result = try {
                client.poll(deviceCode)
            } catch (io: java.io.IOException) {
                continue
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _deviceLoginState.value = DeviceLoginState.Failed(t.message ?: "Device sign-in failed")
                return
            }
            when (result) {
                is DeviceLoginClient.PollResult.Pending -> Unit
                is DeviceLoginClient.PollResult.SlowDown -> intervalSec = result.intervalSec
                is DeviceLoginClient.PollResult.Approved -> {
                    _deviceLoginState.value = DeviceLoginState.Idle
                    TvServices.secureTokenStore(appContext).save(result.session)
                    proceedWithSession(result.session)
                    return
                }
                is DeviceLoginClient.PollResult.Denied -> {
                    _deviceLoginState.value = DeviceLoginState.Denied
                    return
                }
                is DeviceLoginClient.PollResult.Expired -> {
                    _deviceLoginState.value = DeviceLoginState.Expired
                    return
                }
            }
        }
    }

    private fun deviceLoginClient(appContext: Application): DeviceLoginClient? {
        if (!TvServices.backendClient(appContext).isConfigured()) return null
        return DeviceLoginClient(
            httpClient = AppHttp.client(appContext),
            backendUrl = BuildConfig.CRISPY_BACKEND_URL.trim().trimEnd('/'),
            context = appContext,
        )
    }

    private fun formatUserCode(raw: String): String {
        val compact = raw.trim().replace("-", "").uppercase()
        return if (compact.length == 8) {
            "${compact.substring(0, 4)}-${compact.substring(4)}"
        } else {
            raw.trim().uppercase()
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
