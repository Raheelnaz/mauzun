// The dash keeps Java callers away from the published internals below.
@file:JvmName("-HiltMoleculeViewModel")

package io.github.raheelnaz.molecule.hilt

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.raheelnaz.molecule.MoleculeViewModel
import io.github.raheelnaz.molecule.moleculeViewModel

/** Returns a Hilt-created [MoleculeViewModel] with `rememberSaveable` support. */
@Composable
public inline fun <reified VM : MoleculeViewModel<*, *, *>> hiltMoleculeViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
): VM {
    requireUsableKey(key)
    return moleculeViewModel(
        viewModel = hiltViewModel<VM>(viewModelStoreOwner, key),
        key = key,
        viewModelStoreOwner = viewModelStoreOwner,
    )
}

/** Returns an assisted Hilt [MoleculeViewModel] with `rememberSaveable` support. */
@Composable
public inline fun <reified VM : MoleculeViewModel<*, *, *>, reified VMF> hiltMoleculeViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
    noinline creationCallback: (VMF) -> VM,
): VM {
    requireUsableKey(key)
    return moleculeViewModel(
        viewModel = hiltViewModel<VM, VMF>(
            viewModelStoreOwner = viewModelStoreOwner,
            key = key,
            creationCallback = creationCallback,
        ),
        key = key,
        viewModelStoreOwner = viewModelStoreOwner,
    )
}

// hiltViewModel runs before the core module can validate, so the reserved prefix check lives
// here too. It must stay identical to the core module's, and persistence freezes both.
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
