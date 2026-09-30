package com.crispy.tv.search

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun rememberSearchViewModel(
    viewModelFactory: ViewModelProvider.Factory,
    viewModelStoreOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
): SearchViewModel =
    viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        factory = viewModelFactory,
    )
