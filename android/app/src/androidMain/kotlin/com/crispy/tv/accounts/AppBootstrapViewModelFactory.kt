package com.crispy.tv.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * The `androidMain` half of [AppBootstrapViewModel]: construction, nothing else.
 *
 * This is the shape every viewmodel in this repo is heading for. The class itself is
 * in `commonMain` and names only [AccountBootstrapRepository], which is itself an
 * interface, so the class is portable; what is left here is the one thing that cannot
 * be — a `Context` parameter and a reach into [SupabaseServicesProvider], the app's
 * composition root. `androidx.lifecycle:lifecycle-viewmodel` is a genuine KMP
 * artifact, so [ViewModelProvider] itself was never the obstacle.
 */
fun appBootstrapViewModelFactory(context: Context): ViewModelProvider.Factory {
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
