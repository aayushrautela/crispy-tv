package com.crispy.tv.home

import android.content.Context
import com.crispy.tv.platform.android.SharedPreferencesKeyValueStore

/**
 * The `androidMain` construction site for [ContinueWatchingSuppressionStore].
 *
 * The prefs file name lives here rather than in `commonMain` because the file
 * is an Android concern; the key names are in the class itself. Renaming it
 * would strand the dismissals of every install that has any.
 */
fun continueWatchingSuppressionStore(context: Context): ContinueWatchingSuppressionStore =
    ContinueWatchingSuppressionStore(
        SharedPreferencesKeyValueStore(context.applicationContext, "home_continue_watching"),
    )
