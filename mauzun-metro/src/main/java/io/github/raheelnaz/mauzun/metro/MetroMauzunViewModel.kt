// The dash keeps Java callers away from the published internals below.
@file:JvmName("-MetroMauzunViewModel")

package io.github.raheelnaz.mauzun.metro

import androidx.compose.runtime.Composable
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import io.github.raheelnaz.mauzun.MauzunViewModel
import io.github.raheelnaz.mauzun.MauzunViewModelAdapterApi
import io.github.raheelnaz.mauzun.PresenterEntry
import io.github.raheelnaz.mauzun.mauzunPresenterEntry

/** Returns a Metro-created [PresenterEntry] with `rememberSaveable` and lifecycle support. */
@Composable
@OptIn(MauzunViewModelAdapterApi::class)
public inline fun <reified VM : MauzunViewModel<*, *, *>> metroMauzunViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
): PresenterEntry<VM> {
    val extras = defaultCreationExtras(viewModelStoreOwner)
    return mauzunPresenterEntry(
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

/** Returns an assisted Metro [PresenterEntry] with `rememberSaveable` and lifecycle support. */
@Composable
@OptIn(MauzunViewModelAdapterApi::class)
public inline fun <reified VM : MauzunViewModel<*, *, *>> assistedMetroMauzunViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
    extras: CreationExtras = defaultCreationExtras(viewModelStoreOwner),
): PresenterEntry<VM> = mauzunPresenterEntry(
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
 * Returns a manually assisted Metro [PresenterEntry] with `rememberSaveable` and lifecycle
 * support. Metro resolves [FactoryType], and [createViewModel] runs with that factory as its
 * receiver.
 */
@Composable
@OptIn(MauzunViewModelAdapterApi::class)
public inline fun <
    reified VM : MauzunViewModel<*, *, *>,
    reified FactoryType : ManualViewModelAssistedFactory,
> assistedMetroMauzunViewModel(
    viewModelStoreOwner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
        },
    key: String? = null,
    extras: CreationExtras = defaultCreationExtras(viewModelStoreOwner),
    crossinline createViewModel: FactoryType.(CreationExtras) -> VM,
): PresenterEntry<VM> = mauzunPresenterEntry(
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
