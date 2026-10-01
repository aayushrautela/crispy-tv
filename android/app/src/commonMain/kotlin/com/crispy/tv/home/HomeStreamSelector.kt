package com.crispy.tv.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crispy.tv.streams.StreamSelectorSheet

/**
 * [isCompact] crossed as data rather than read here.
 *
 * `LocalConfiguration` is Android-only — it lives in `ui-android`'s
 * `AndroidCompositionLocals_androidKt` — so a `commonMain` composable cannot
 * name it, and **no import scan, no reverse audit and no jar-grep can see that
 * dependency at all.** `isWideScreen`, `isCompact` and `pluginsUiSupported`
 * already crossed this way in earlier landings, so this is the same shape
 * arriving a second time and not a new one.
 *
 * No default, because a defaulted slot lets a call site silently forget it.
 */
@Composable
internal fun HomeStreamSelector(
    viewModel: HomeSelectorViewModel,
    isCompact: Boolean,
) {
    val state by viewModel.coordinator.state.collectAsStateWithLifecycle()
    val details by viewModel.coordinator.details.collectAsStateWithLifecycle()
    val headerEpisode by viewModel.coordinator.headerEpisode.collectAsStateWithLifecycle()

    StreamSelectorSheet(
        visible = state.visible,
        state = state,
        details = details,
        headerEpisode = headerEpisode,
        accentColor = MaterialTheme.colorScheme.primary,
        onAccentColor = MaterialTheme.colorScheme.onPrimary,
        isCompact = isCompact,
        onDismiss = viewModel::dismiss,
        onProviderSelected = viewModel.coordinator::onProviderSelected,
        onStreamSelected = viewModel.coordinator::onStreamSelected,
    )
}
