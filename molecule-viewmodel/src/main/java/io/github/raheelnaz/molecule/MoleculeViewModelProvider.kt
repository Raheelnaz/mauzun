package io.github.raheelnaz.molecule

import androidx.compose.runtime.Composable
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel as androidxViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * Returns a [MoleculeViewModel] scoped to [viewModelStoreOwner]. Presenters obtained here can use
 * `rememberSaveable` without accepting a `SavedStateHandle` themselves. The owner must provide the
 * saved-state creation extras supplied by an Activity, Fragment, or navigation entry.
 */
@Composable
public inline fun <reified VM : MoleculeViewModel<*, *, *>> moleculeViewModel(
    key: String? = null,
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    factory: ViewModelProvider.Factory? = null,
    extras: CreationExtras = viewModelStoreOwner.defaultCreationExtras(),
): VM {
    val presenter = androidxViewModel<VM>(
        viewModelStoreOwner = viewModelStoreOwner,
        key = key,
        factory = factory,
        extras = extras,
    )
    return moleculeViewModel(
        viewModel = presenter,
        key = key,
        viewModelStoreOwner = viewModelStoreOwner,
        extras = extras,
    )
}

/**
 * Adds `rememberSaveable` support to [viewModel] when another integration created it. Use the same
 * [key] and [viewModelStoreOwner] that were used to obtain the ViewModel, before reading its state.
 */
@Composable
public inline fun <reified VM : MoleculeViewModel<*, *, *>> moleculeViewModel(
    viewModel: VM,
    key: String? = null,
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    extras: CreationExtras = viewModelStoreOwner.defaultCreationExtras(),
): VM = attachMoleculeSavedState(
    viewModel = viewModel,
    viewModelKey = key ?: defaultViewModelKey(VM::class.java),
    viewModelStoreOwner = viewModelStoreOwner,
    extras = extras,
)

// The same fallback androidx's viewModel() uses when the owner has no factory support.
@PublishedApi
internal fun ViewModelStoreOwner.defaultCreationExtras(): CreationExtras =
    if (this is HasDefaultViewModelProviderFactory) {
        defaultViewModelCreationExtras
    } else {
        CreationExtras.Empty
    }

@PublishedApi
internal fun defaultViewModelKey(modelClass: Class<*>): String {
    val canonicalName = modelClass.canonicalName
        ?: throw IllegalArgumentException("Local and anonymous classes can not be ViewModels")
    return "androidx.lifecycle.ViewModelProvider.DefaultKey:$canonicalName"
}

private const val SAVED_STATE_HOLDER_KEY_PREFIX =
    "io.github.raheelnaz.molecule.presenter-saved-state-holder:"

private class PresenterSavedStateHolder(handle: SavedStateHandle) : ViewModel() {
    val savedState = PresenterSavedState(handle)
}

private val presenterSavedStateHolderFactory = viewModelFactory {
    initializer { PresenterSavedStateHolder(createSavedStateHandle()) }
}

@Composable
@PublishedApi
internal fun <VM : MoleculeViewModel<*, *, *>> attachMoleculeSavedState(
    viewModel: VM,
    viewModelKey: String,
    viewModelStoreOwner: ViewModelStoreOwner,
    extras: CreationExtras,
): VM {
    val holder = androidxViewModel<PresenterSavedStateHolder>(
        viewModelStoreOwner = viewModelStoreOwner,
        key = SAVED_STATE_HOLDER_KEY_PREFIX + viewModelKey,
        factory = presenterSavedStateHolderFactory,
        extras = extras,
    )
    viewModel.attachSavedState(holder.savedState)
    return viewModel
}
