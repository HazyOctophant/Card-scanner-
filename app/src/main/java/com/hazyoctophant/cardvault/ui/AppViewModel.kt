package com.hazyoctophant.cardvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hazyoctophant.cardvault.AppContainer
import com.hazyoctophant.cardvault.CardVaultApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as CardVaultApp).container

/** Creates a ViewModel wired to the app's dependencies. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
