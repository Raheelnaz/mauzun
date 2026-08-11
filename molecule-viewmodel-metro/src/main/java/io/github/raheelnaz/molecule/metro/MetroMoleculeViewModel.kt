// The dash keeps Java callers away from the published internals below.
@file:JvmName("-MetroMoleculeViewModel")

package io.github.raheelnaz.molecule.metro

import androidx.compose.runtime.Composable
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.raheelnaz.molecule.MoleculeViewModel
import io.github.raheelnaz.molecule.MoleculeViewModelAdapterApi
import io.github.raheelnaz.molecule.PresenterEntry
import io.github.raheelnaz.molecule.moleculePresenterEntry

/** Returns a Metro-created [PresenterEntry] with `rememberSaveable` support. */
@Composable
@OptIn(MoleculeViewModelAdapterApi::class)
public inline fun <reified VM : MoleculeViewModel<*, *, *>> metroMoleculeViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
): PresenterEntry<VM> {
    val extras = defaultCreationExtras(viewModelStoreOwner)
    return moleculePresenterEntry(
        key = key,
        modelClass = VM::class.java,
        viewModelStoreOwner = viewModelStoreOwner,
        extras = extras,
    ) {
        metroViewModel<VM>(
            viewModelStoreOwner = viewModelStoreOwner,
            key = key,
        )
    }
}

/** Returns an assisted Metro [PresenterEntry] with `rememberSaveable` support. */
@Composable
@OptIn(MoleculeViewModelAdapterApi::class)
public inline fun <reified VM : MoleculeViewModel<*, *, *>> assistedMetroMoleculeViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
    extras: CreationExtras = defaultCreationExtras(viewModelStoreOwner),
): PresenterEntry<VM> = moleculePresenterEntry(
    key = key,
    modelClass = VM::class.java,
    viewModelStoreOwner = viewModelStoreOwner,
    extras = extras,
) {
    assistedMetroViewModel<VM>(
        viewModelStoreOwner = viewModelStoreOwner,
        key = key,
        extras = extras,
    )
}

/**
 * Returns a manually assisted Metro [PresenterEntry] with `rememberSaveable` support. Metro
 * resolves [FactoryType], and [createViewModel] runs with that factory as its receiver.
 */
@Composable
@OptIn(MoleculeViewModelAdapterApi::class)
public inline fun <
    reified VM : MoleculeViewModel<*, *, *>,
    reified FactoryType : ManualViewModelAssistedFactory,
> assistedMetroMoleculeViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
    extras: CreationExtras = defaultCreationExtras(viewModelStoreOwner),
    crossinline createViewModel: FactoryType.(CreationExtras) -> VM,
): PresenterEntry<VM> = moleculePresenterEntry(
    key = key,
    modelClass = VM::class.java,
    viewModelStoreOwner = viewModelStoreOwner,
    extras = extras,
) {
    assistedMetroViewModel<VM, FactoryType>(
        viewModelStoreOwner = viewModelStoreOwner,
        key = key,
        extras = extras,
        createViewModel = createViewModel,
    )
}

@PublishedApi
internal fun defaultCreationExtras(owner: ViewModelStoreOwner): CreationExtras =
    if (owner is HasDefaultViewModelProviderFactory) {
        owner.defaultViewModelCreationExtras
    } else {
        CreationExtras.Empty
    }
