// The dash keeps Java callers away from the published internals below.
@file:JvmName("-MoleculeViewModelProvider")

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
    extras: CreationExtras = if (viewModelStoreOwner is HasDefaultViewModelProviderFactory) {
        viewModelStoreOwner.defaultViewModelCreationExtras
    } else {
        CreationExtras.Empty
    },
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
    extras: CreationExtras = if (viewModelStoreOwner is HasDefaultViewModelProviderFactory) {
        viewModelStoreOwner.defaultViewModelCreationExtras
    } else {
        CreationExtras.Empty
    },
): VM = attachMoleculeSavedState(
    viewModel = viewModel,
    key = key,
    modelClass = VM::class.java,
    viewModelStoreOwner = viewModelStoreOwner,
    extras = extras,
)

// Prefixed so a derived key can never collide with an explicit one.
private fun defaultViewModelKey(modelClass: Class<*>): String {
    val canonicalName = modelClass.canonicalName
        ?: throw IllegalArgumentException("Local and anonymous classes can not be ViewModels")
    return "io.github.raheelnaz.molecule.default-key:$canonicalName"
}

private const val SAVED_STATE_HOLDER_KEY_PREFIX =
    "io.github.raheelnaz.molecule.presenter-saved-state-holder:"

private class PresenterSavedStateHolder(handle: SavedStateHandle) : ViewModel() {
    val savedState = PresenterSavedState(handle)
}

private val presenterSavedStateHolderFactory = viewModelFactory {
    initializer {
        // androidx throws IllegalArgumentException for a missing extra and IllegalStateException
        // for missing setup; the try covers that one call, so catch both.
        val handle = try {
            createSavedStateHandle()
        } catch (failure: RuntimeException) {
            throw IllegalStateException(
                "moleculeViewModel needs a ViewModelStoreOwner that provides saved state. " +
                    "An Activity, Fragment, or navigation entry does; this owner does not.",
                failure,
            )
        }
        PresenterSavedStateHolder(handle)
    }
}

@Composable
@PublishedApi
internal fun <VM : MoleculeViewModel<*, *, *>> attachMoleculeSavedState(
    viewModel: VM,
    key: String?,
    modelClass: Class<out VM>,
    viewModelStoreOwner: ViewModelStoreOwner,
    extras: CreationExtras,
): VM {
    val viewModelKey = key ?: defaultViewModelKey(modelClass)
    // A presenter keyed under this prefix would replace another screen's holder, and that
    // screen's saved state would be lost.
    require(!viewModelKey.startsWith(SAVED_STATE_HOLDER_KEY_PREFIX)) {
        "ViewModel keys starting with $SAVED_STATE_HOLDER_KEY_PREFIX are reserved"
    }
    val holder = androidxViewModel<PresenterSavedStateHolder>(
        viewModelStoreOwner = viewModelStoreOwner,
        key = SAVED_STATE_HOLDER_KEY_PREFIX + viewModelKey,
        factory = presenterSavedStateHolderFactory,
        extras = extras,
    )
    viewModel.attachSavedState(holder.savedState)
    return viewModel
}
