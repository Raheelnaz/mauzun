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
): VM = moleculeViewModel(
    viewModel = hiltViewModel<VM>(viewModelStoreOwner, key),
    key = key,
    viewModelStoreOwner = viewModelStoreOwner,
)

/** Returns an assisted Hilt [MoleculeViewModel] with `rememberSaveable` support. */
@Composable
public inline fun <reified VM : MoleculeViewModel<*, *, *>, reified VMF> hiltMoleculeViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
    noinline creationCallback: (VMF) -> VM,
): VM = moleculeViewModel(
    viewModel = hiltViewModel<VM, VMF>(
        viewModelStoreOwner = viewModelStoreOwner,
        key = key,
        creationCallback = creationCallback,
    ),
    key = key,
    viewModelStoreOwner = viewModelStoreOwner,
)
