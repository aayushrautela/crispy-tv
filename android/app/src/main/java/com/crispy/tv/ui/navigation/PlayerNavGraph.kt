package com.crispy.tv.ui.navigation

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.playerui.LocalPlayerHost
import com.crispy.tv.playerui.PlayerRoute
import com.crispy.tv.playerui.PlayerSessionViewModel
import com.crispy.tv.playerui.findActivity

internal fun NavGraphBuilder.addPlayerDestination(navController: NavHostController) {
    composable(
        route = AppRoutes.PlayerRoutePattern,
        arguments = listOf(
            navArgument(AppRoutes.PlayerMediaTypeArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerItemIdArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerSeriesItemIdArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerImdbIdArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerSeasonArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerEpisodeArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerYearArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerShowTitleArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerShowYearArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerParentMediaTypeArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerAbsoluteEpisodeNumberArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerResumePositionMsArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerChosenStreamKeyArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerChosenProviderIdArg) { type = NavType.StringType; defaultValue = "" },
            navArgument(AppRoutes.PlayerChosenStreamHandoffKeyArg) { type = NavType.StringType; defaultValue = "" },
        ),
    ) { entry ->
        val context = LocalContext.current
        val appContext = remember(context) { context.applicationContext }
        val identity = remember(entry) { parsePlayerIdentity(entry.arguments) }
        if (identity == null) {
            // Unusable launch payload (e.g. after process death): leave gracefully.
            LaunchedEffect(Unit) { navController.popBackStack() }
            return@composable
        }

        val host = LocalPlayerHost.current
        val resumePositionMs =
            entry.arguments?.getString(AppRoutes.PlayerResumePositionMsArg)?.toLongOrNull() ?: 0L
        val chosenStreamStableKey = optionalArg(entry.arguments, AppRoutes.PlayerChosenStreamKeyArg)
        val chosenProviderId = optionalArg(entry.arguments, AppRoutes.PlayerChosenProviderIdArg)
        val chosenStreamHandoffKey =
            optionalArg(entry.arguments, AppRoutes.PlayerChosenStreamHandoffKeyArg)

        val sessionViewModel: PlayerSessionViewModel = viewModel(
            factory = remember(appContext, identity, resumePositionMs) {
                PlayerSessionViewModel.factory(
                    appContext = appContext,
                    identity = identity,
                    resumePositionMs = resumePositionMs,
                    chosenStreamStableKey = chosenStreamStableKey,
                    chosenProviderId = chosenProviderId,
                    chosenStreamHandoffKey = chosenStreamHandoffKey,
                )
            },
        )

        PlayerWindowPolicyEffect()
        ForegroundPlaybackEffect(entry, sessionViewModel, host.isInPictureInPictureMode)
        LaunchedEffect(host) { host.maybeRequestNotificationPermission() }

        PlayerRoute(
            session = sessionViewModel,
            isInPictureInPictureMode = host.isInPictureInPictureMode.value,
            onPictureInPictureConfigChanged = host::updatePictureInPictureConfig,
            onBack = { navController.popBackStack() },
        )
    }
}

private fun optionalArg(arguments: Bundle?, key: String): String? =
    arguments?.getString(key)?.trim()?.ifBlank { null }

private fun parsePlayerIdentity(arguments: Bundle?): PlaybackIdentity? {
    val mediaTypeName = arguments?.getString(AppRoutes.PlayerMediaTypeArg).orEmpty().trim()
    val contentType = runCatching { MetadataLabMediaType.valueOf(mediaTypeName) }.getOrNull()
        ?: return null

    fun string(key: String): String? = arguments?.getString(key)?.trim()?.ifBlank { null }
    fun int(key: String): Int? = string(key)?.toIntOrNull()?.takeIf { it > 0 }

    val showTitle = string(AppRoutes.PlayerShowTitleArg)
    return PlaybackIdentity(
        itemId = string(AppRoutes.PlayerItemIdArg),
        seriesItemId = string(AppRoutes.PlayerSeriesItemIdArg),
        imdbId = string(AppRoutes.PlayerImdbIdArg),
        contentType = contentType,
        season = int(AppRoutes.PlayerSeasonArg),
        episode = int(AppRoutes.PlayerEpisodeArg),
        title = showTitle ?: "",
        year = int(AppRoutes.PlayerYearArg),
        showTitle = showTitle,
        showYear = int(AppRoutes.PlayerShowYearArg),
        parentMediaType = string(AppRoutes.PlayerParentMediaTypeArg),
        absoluteEpisodeNumber = int(AppRoutes.PlayerAbsoluteEpisodeNumberArg),
    )
}

/**
 * Immersive video window while the player destination is visible: landscape lock, screen kept
 * on, cutout short edges and hidden system bars. Everything is restored when leaving so the
 * rest of the app keeps its normal presentation.
 */
@Composable
private fun PlayerWindowPolicyEffect() {
    val activity: Activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
        val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Pauses/resumes playback as the player destination becomes covered or visible again, mirroring
 * start/stop semantics. Picture-in-picture is exempt: playback continues behind the pip window.
 */
@Composable
private fun ForegroundPlaybackEffect(
    lifecycleOwner: LifecycleOwner,
    session: PlayerSessionViewModel,
    isInPictureInPictureMode: State<Boolean>,
) {
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START ->
                    if (!isInPictureInPictureMode.value) {
                        session.onForegroundStart()
                    }

                Lifecycle.Event.ON_STOP ->
                    if (!isInPictureInPictureMode.value) {
                        session.onBackgroundStop()
                    }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
