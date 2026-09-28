package com.crispy.tv.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun rememberSearchViewModel(
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
): SearchViewModel {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    return viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        factory = remember(appContext) {
            SearchViewModel.factory(appContext)
        },
    )
}
