/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.hilt.navigation.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.HiltViewModelFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.getBackStackEntry
import kotlin.reflect.KClass

/**
 * Returns an existing
 * [HiltViewModel](https://dagger.dev/api/latest/dagger/hilt/android/lifecycle/HiltViewModel)
 * -annotated [ViewModel] or creates a new one scoped to the current navigation graph present on
 * the {@link NavController} back stack.
 *
 * If no navigation graph is currently present then the current scope will be used, usually, a
 * fragment or an activity.
 *
 * @sample androidx.hilt.navigation.compose.samples.NavComposable
 */
@Composable
inline fun <reified VM : ViewModel> hiltViewModel(): VM {
    val owner = LocalViewModelStoreOwner.current
    return if (owner is NavBackStackEntry) {
        hiltViewModel(VM::class, owner)
    } else {
        viewModel()
    }
}

/**
 * Returns an existing
 * [HiltViewModel](https://dagger.dev/api/latest/dagger/hilt/android/lifecycle/HiltViewModel)
 * -annotated [ViewModel] or creates a new one scoped to the current navigation graph present on
 * the {@link NavController} back stack.
 *
 * If no navigation graph is currently present then the current scope will be used, usually, a
 * fragment or an activity.
 */
@Deprecated(
    message = "Use hiltViewModel() instead.",
    replaceWith = ReplaceWith("hiltViewModel()"),
)
@Composable
inline fun <reified VM : ViewModel> hiltNavGraphViewModel() = hiltViewModel<VM>()

/**
 * Returns an existing
 * [HiltViewModel](https://dagger.dev/api/latest/dagger/hilt/android/lifecycle/HiltViewModel)
 * -annotated [ViewModel] or creates a new one scoped to the current navigation graph present on
 * the [NavController] back stack.
 *
 * @param backStackEntry The entry of a [NavController] back stack.
 *
 * @sample androidx.hilt.navigation.compose.samples.NestedNavComposable
 */
@Composable
inline fun <reified VM : ViewModel> hiltViewModel(backStackEntry: NavBackStackEntry) =
    hiltViewModel(VM::class, backStackEntry)

/**
 * Returns an existing
 * [HiltViewModel](https://dagger.dev/api/latest/dagger/hilt/android/lifecycle/HiltViewModel)
 * -annotated [ViewModel] or creates a new one scoped to the current navigation graph present on
 * the [NavController] back stack.
 *
 * @param backStackEntry The entry of a [NavController] back stack.
 */
@Deprecated(
    message = "Use hiltViewModel(NavBackStackEntry) instead.",
    replaceWith = ReplaceWith("hiltViewModel(backStackEntry)"),
)
@Composable
inline fun <reified VM : ViewModel> hiltNavGraphViewModel(backStackEntry: NavBackStackEntry) =
    hiltViewModel<VM>(backStackEntry)

/**
 * Returns an existing
 * [HiltViewModel](https://dagger.dev/api/latest/dagger/hilt/android/lifecycle/HiltViewModel)
 * -annotated [ViewModel] or creates a new one scoped to the current navigation graph present on
 * the [NavController] back stack.
 *
 * @param route route of a destination that exists on the [NavController] back stack.
 */
@Deprecated(
    message = "Use hiltViewModel(NavBackStackEntry) in combination with " +
        "NavController#getBackStackEntry(String). This API will be removed in a future version.",
    replaceWith = ReplaceWith("hiltViewModel(this.getBackStackEntry(route))"),
    level = DeprecationLevel.ERROR
)
@Composable
inline fun <reified VM : ViewModel> NavController.hiltNavGraphViewModel(route: String) =
    hiltViewModel<VM>(this.getBackStackEntry(route))

@Composable
@PublishedApi
internal fun <VM : ViewModel> hiltViewModel(
    kClass: KClass<VM>,
    backStackEntry: NavBackStackEntry
): VM {
    val viewModelFactory = HiltViewModelFactory(
        context = LocalContext.current,
        navBackStackEntry = backStackEntry
    )
    return ViewModelProvider(backStackEntry, viewModelFactory).get(kClass.java)
}