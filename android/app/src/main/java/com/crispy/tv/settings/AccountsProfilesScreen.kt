package com.crispy.tv.settings

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.BuildConfig
import com.crispy.tv.accounts.AccountPortalUrls
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val AccountPortalLogTag = "AccountPortalWebView"

@Immutable
data class AccountUiState(
    val supabaseConfigured: Boolean = false,
    val portalConfigured: Boolean = false,
    val isBusy: Boolean = false,
    val authenticated: Boolean = false,
    val email: String? = null,
    val statusMessage: String = "",
    val pendingPortalUrl: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsProfilesRoute(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val viewModel: AccountsPortalViewModel = viewModel(
        factory = remember(appContext) { AccountsPortalViewModel.factory(appContext) }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = appBarScrollBehavior()
    val pageHorizontalPadding = responsivePageHorizontalPadding()

    var mode by remember { mutableStateOf(0) }
    var signInEmail by remember { mutableStateOf("") }
    var signInPassword by remember { mutableStateOf("") }
    var signUpEmail by remember { mutableStateOf("") }
    var signUpPassword by remember { mutableStateOf("") }

    val topBarTitle = when {
        uiState.pendingPortalUrl != null -> "Account & Subscription"
        else -> "Account"
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = topBarTitle,
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.pendingPortalUrl != null) {
                            viewModel.clearPortalUrl()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        when {
            uiState.pendingPortalUrl != null -> {
                AccountPortalWebView(
                    url = uiState.pendingPortalUrl!!,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }

            !uiState.authenticated -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(
                        start = pageHorizontalPadding,
                        end = pageHorizontalPadding,
                        top = Dimensions.SectionSpacing,
                        bottom = Dimensions.PageBottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.statusMessage.isNotBlank()) {
                        item {
                            Text(
                                text = uiState.statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (!uiState.supabaseConfigured) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(Dimensions.CardInternalPadding)) {
                                    Text(
                                        text = "Authentication is not configured.",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "Set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY in your Gradle properties.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (mode == 0) {
                        item {
                            OutlinedTextField(
                                value = signInEmail,
                                onValueChange = { signInEmail = it.trim() },
                                label = { Text("Email") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = signInPassword,
                                onValueChange = { signInPassword = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = { viewModel.signIn(signInEmail, signInPassword) },
                                enabled = !uiState.isBusy && signInEmail.isNotBlank() && signInPassword.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Sign in")
                            }
                        }
                        item {
                            TextButton(
                                onClick = { mode = 1; signInEmail = ""; signInPassword = "" },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Create account")
                            }
                        }
                    } else {
                        item {
                            OutlinedTextField(
                                value = signUpEmail,
                                onValueChange = { signUpEmail = it.trim() },
                                label = { Text("Email") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = signUpPassword,
                                onValueChange = { signUpPassword = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Button(
                                onClick = { viewModel.signUp(signUpEmail, signUpPassword) },
                                enabled = !uiState.isBusy && signUpEmail.isNotBlank() && signUpPassword.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Create account")
                            }
                        }
                        item {
                            TextButton(
                                onClick = { mode = 0; signUpEmail = ""; signUpPassword = "" },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Already have an account? Sign in")
                            }
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(
                        start = pageHorizontalPadding,
                        end = pageHorizontalPadding,
                        top = Dimensions.SectionSpacing,
                        bottom = Dimensions.PageBottomPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.statusMessage.isNotBlank()) {
                        item {
                            Text(
                                text = uiState.statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(Dimensions.CardInternalPadding)) {
                                Text(
                                    text = "Signed in",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = uiState.email ?: "(unknown)",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { viewModel.openAccountPortal() },
                                        enabled = uiState.portalConfigured && !uiState.isBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Account and subscription")
                                    }
                                    OutlinedButton(
                                        onClick = viewModel::signOut,
                                        enabled = !uiState.isBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Sign out")
                                    }
                                    OutlinedButton(
                                        onClick = onOpenSettings,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Settings")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountPortalWebView(
    url: String,
    modifier: Modifier = Modifier,
) {
    var isLoading by remember { mutableStateOf(true) }
    val sessionProbeScript = remember { portalSessionProbeScript() }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    val thirdPartyBefore = cookieManager.acceptThirdPartyCookies(this)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    val thirdPartyAfter = cookieManager.acceptThirdPartyCookies(this)
                    Log.d(
                        AccountPortalLogTag,
                        "open url=${redactUrlForLog(url)} api=${redactUrlForLog(BuildConfig.CRISPY_BACKEND_URL)} cookies=${cookieManager.acceptCookie()} thirdPartyBefore=$thirdPartyBefore thirdPartyAfter=$thirdPartyAfter webDebug=${BuildConfig.DEBUG}"
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            Log.d(
                                AccountPortalLogTag,
                                "console level=${consoleMessage?.messageLevel()} source=${redactForLog(consoleMessage?.sourceId())}:${consoleMessage?.lineNumber()} message=${redactForLog(consoleMessage?.message())}"
                            )
                            return false
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            isLoading = true
                            logPortalCookieState("pageStarted", url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            logPortalCookieState("pageFinished", url)
                            view?.evaluateJavascript(sessionProbeScript) { result ->
                                Log.d(AccountPortalLogTag, "sessionProbe result=${redactForLog(result)}")
                            }
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            Log.w(
                                AccountPortalLogTag,
                                "resourceError mainFrame=${request?.isForMainFrame} url=${redactUrlForLog(request?.url?.toString())} code=${error?.errorCode} description=${redactForLog(error?.description?.toString())}"
                            )
                        }

                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                            Log.w(
                                AccountPortalLogTag,
                                "httpError mainFrame=${request?.isForMainFrame} url=${redactUrlForLog(request?.url?.toString())} status=${errorResponse?.statusCode} reason=${redactForLog(errorResponse?.reasonPhrase)}"
                            )
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            Log.d(AccountPortalLogTag, "navigation url=${redactUrlForLog(request?.url?.toString())}")
                            return false
                        }
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

private fun logPortalCookieState(stage: String, pageUrl: String?) {
    val cookieManager = CookieManager.getInstance()
    val apiCookies = cookieManager.getCookie(BuildConfig.CRISPY_BACKEND_URL)
    val pageCookies = pageUrl?.let { runCatching { cookieManager.getCookie(it) }.getOrNull() }
    Log.d(
        AccountPortalLogTag,
        "$stage page=${redactUrlForLog(pageUrl)} apiCookiePresent=${apiCookies?.contains("crispy_portal_session") == true} apiCookieNames=${cookieNamesForLog(apiCookies)} pageCookieNames=${cookieNamesForLog(pageCookies)}"
    )
}

private fun cookieNamesForLog(cookies: String?): String {
    if (cookies.isNullOrBlank()) return "[]"
    return cookies
        .split(';')
        .mapNotNull { part -> part.trim().substringBefore('=', "").takeIf { it.isNotBlank() } }
        .joinToString(prefix = "[", postfix = "]")
}

private fun redactUrlForLog(raw: String?): String {
    if (raw.isNullOrBlank()) return "null"
    return runCatching {
        val uri = Uri.parse(raw)
        buildString {
            append(uri.scheme ?: "")
            append("://")
            append(uri.host ?: "")
            if (uri.port != -1) append(":").append(uri.port)
            append(uri.path ?: "")
            if (!uri.query.isNullOrBlank()) append("?redactedQuery=true")
        }
    }.getOrElse { "invalid-url" }
}

private fun redactForLog(value: String?): String {
    if (value.isNullOrBlank()) return "null"
    return value
        .replace(Regex("cp_ph_[A-Za-z0-9_-]+"), "cp_ph_[redacted]")
        .replace(Regex("crispy_portal_session=[^;\\s]+"), "crispy_portal_session=[redacted]")
}

private fun portalSessionProbeScript(): String {
    val apiBaseUrl = BuildConfig.CRISPY_BACKEND_URL.replace("\\", "\\\\").replace("'", "\\'")
    return """
        (async function() {
          try {
            const response = await fetch('$apiBaseUrl/v1/auth/portal/session', { credentials: 'include' });
            const body = await response.json().catch(() => null);
            const data = body && (body.data || body);
            console.log('[crispy-portal-probe] status=' + response.status + ' user=' + Boolean(data && data.user) + ' csrf=' + Boolean(data && data.csrfToken));
            return JSON.stringify({ status: response.status, user: Boolean(data && data.user), csrf: Boolean(data && data.csrfToken) });
          } catch (error) {
            console.log('[crispy-portal-probe] error=' + String(error));
            return JSON.stringify({ error: String(error) });
          }
        })();
    """.trimIndent()
}

internal class AccountsPortalViewModel(
    private val supabase: SupabaseAccountClient,
    private val backend: CrispyBackendClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AccountUiState(
            supabaseConfigured = supabase.isConfigured(),
            portalConfigured = AccountPortalUrls.isConfigured(),
        ),
    )
    val uiState: StateFlow<AccountUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            if (session == null) {
                _uiState.update {
                    it.copy(isBusy = false, authenticated = false, email = null)
                }
                return@launch
            }
            _uiState.update {
                it.copy(isBusy = false, authenticated = true, email = session.email)
            }
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            try {
                supabase.signInWithEmail(email.trim(), password)
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, statusMessage = e.message ?: "Sign in failed.")
                }
            }
        }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            try {
                val result = supabase.signUpWithEmail(email.trim(), password)
                if (result.session != null) {
                    refresh()
                } else {
                    _uiState.update {
                        it.copy(isBusy = false, statusMessage = result.message)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isBusy = false, statusMessage = e.message ?: "Sign up failed.")
                }
            }
        }
    }

    fun openAccountPortal() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            if (session != null) {
                try {
                    val result = backend.createPortalHandoffCode(session.accessToken, "/account")
                    _uiState.update { it.copy(pendingPortalUrl = result.portalUrl, isBusy = false) }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(isBusy = false, statusMessage = e.message ?: "Failed to open portal.")
                    }
                }
            } else {
                _uiState.update {
                    it.copy(isBusy = false, statusMessage = "Session expired. Sign in again.")
                }
            }
        }
    }

    fun clearPortalUrl() {
        _uiState.update { it.copy(pendingPortalUrl = null) }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            runCatching { supabase.signOut() }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    authenticated = false,
                    email = null,
                    statusMessage = "Signed out.",
                )
            }
        }
    }

    companion object {
        fun factory(appContext: android.content.Context): ViewModelProvider.Factory {
            val safeContext = appContext.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val supabase = SupabaseServicesProvider.accountClient(safeContext)
                    val backend = BackendServicesProvider.backendClient(safeContext)
                    return AccountsPortalViewModel(
                        supabase = supabase,
                        backend = backend,
                    ) as T
                }
            }
        }
    }
}
