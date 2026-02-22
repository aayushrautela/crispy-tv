package com.crispy.tv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import com.crispy.tv.oauth.OAuthCallbackHandler
import com.crispy.tv.startup.AppStartup
import com.crispy.tv.ui.AppRoot
import com.crispy.tv.ui.theme.CrispyRewriteTheme

class MainActivity : ComponentActivity() {
    private val oauthCallbackHandler by lazy(LazyThreadSafetyMode.NONE) {
        OAuthCallbackHandler(appContext = applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        configureEdgeToEdge()
        AppStartup.run(applicationContext)
        oauthCallbackHandler.handle(intent)

        setContent {
            CrispyRewriteTheme {
                DisposableEffect(Unit) {
                    configureSystemBars(isDark = true)
                    onDispose { }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        oauthCallbackHandler.handle(intent)
    }

    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun configureSystemBars(isDark: Boolean) {
        clearSystemBarBackgrounds()
        disableSystemBarContrast()
        applySystemBarIconAppearance(isDark)
    }

    @Suppress("DEPRECATION")
    private fun clearSystemBarBackgrounds() {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
    }

    @Suppress("DEPRECATION")
    private fun disableSystemBarContrast() {
        if (Build.VERSION.SDK_INT < 29) {
            return
        }

        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    private fun applySystemBarIconAppearance(isDark: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }
}
