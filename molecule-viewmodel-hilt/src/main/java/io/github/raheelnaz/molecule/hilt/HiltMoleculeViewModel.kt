// The dash keeps Java callers away from the published internals below.
@file:JvmName("-HiltMoleculeViewModel")

package io.github.raheelnaz.molecule.hilt

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.raheelnaz.molecule.MoleculeViewModel
import io.github.raheelnaz.molecule.MoleculeViewModelAdapterApi
import io.github.raheelnaz.molecule.PresenterEntry
import io.github.raheelnaz.molecule.moleculePresenterEntry

/** Returns a Hilt-created [PresenterEntry] with `rememberSaveable` support. */
@Composable
@OptIn(MoleculeViewModelAdapterApi::class)
public inline fun <reified VM : MoleculeViewModel<*, *, *>> hiltMoleculeViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
): PresenterEntry<VM> {
    requireUsableKey(key)
    return moleculePresenterEntry(
        key = key,
        modelClass = VM::class.java,
        viewModelStoreOwner = viewModelStoreOwner,
        extras = defaultCreationExtras(viewModelStoreOwner),
    ) {
        hiltViewModel<VM>(viewModelStoreOwner, key)
    }
}

/** Returns an assisted Hilt [PresenterEntry] with `rememberSaveable` support. */
@Composable
@OptIn(MoleculeViewModelAdapterApi::class)
public inline fun <reified VM : MoleculeViewModel<*, *, *>, reified VMF> hiltMoleculeViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
    noinline creationCallback: (VMF) -> VM,
): PresenterEntry<VM> {
    requireUsableKey(key)
    return moleculePresenterEntry(
        key = key,
        modelClass = VM::class.java,
        viewModelStoreOwner = viewModelStoreOwner,
        extras = defaultCreationExtras(viewModelStoreOwner),
    ) {
        hiltViewModel<VM, VMF>(
            viewModelStoreOwner = viewModelStoreOwner,
            key = key,
            creationCallback = creationCallback,
        )
    }
}

@PublishedApi
internal fun defaultCreationExtras(owner: ViewModelStoreOwner): CreationExtras =
    if (owner is HasDefaultViewModelProviderFactory) {
        owner.defaultViewModelCreationExtras
    } else {
        CreationExtras.Empty
    }

// The core module checks this too, but here a bad key fails before any hilt code runs. Keep
// it identical to the core check. The prefix can never change once devices hold saved state.
@PublishedApi
internal fun requireUsableKey(key: String?) {
    require(
        key == null ||
            !key.startsWith("io.github.raheelnaz.molecule.presenter-saved-state-holder:"),
    ) {
        "ViewModel keys starting with " +
            "io.github.raheelnaz.molecule.presenter-saved-state-holder: are reserved"
    }
}
