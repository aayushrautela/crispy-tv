package com.crispy.tv

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.startup.AppStartup
import com.crispy.tv.ui.AppRoot
import com.crispy.tv.ui.components.ProvideCrispyImageSettings
import com.crispy.tv.ui.theme.CrispyRewriteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        AppStartup.run(applicationContext)
        handleDeepLink(intent)

        setContent {
            ProvideCrispyImageSettings {
                CrispyRewriteTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppRoot()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        val data = intent.data ?: return
        val code = data.getQueryParameter("code")
        if (code.isNullOrBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val appContext = applicationContext
                val backend = BackendServicesProvider.backendClient(appContext)
                val supabase = SupabaseServicesProvider.accountClient(appContext)

                val result = backend.exchangeAppLoginCode(code, Build.MODEL)
                supabase.saveAppSession(result.plaintextToken, result.userId, result.email)

                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Signed in successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val message = e.message ?: "Unknown error"
                    Toast.makeText(appContext, "Sign in failed: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
