package com.crispy.tv.accounts

import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.AppGraph

/**
 * The bootstrap viewmodel's factory, and the only one of the four that is not needed twice.
 *
 * `appBootstrapViewModelFactory` is in `androidMain` because `ViewModelProvider.Factory`'s
 * common metadata declares only `create(KClass<T>, extras)` and
 * `java.lang.Class` cannot be written in `commonMain` — see
 * [AccountViewModelBuilders] for the measurement, and the desktop account factories beside
 * this file for the other three.
 */
fun appBootstrapViewModelFactory(graph: AppGraph): ViewModelProvider.Factory =
    viewModelFactoryOf { buildAppBootstrapViewModel(graph) }