// The dash keeps Java callers away from the published internals below.
@file:JvmName("-MauzunViewModelProvider")

package io.github.raheelnaz.mauzun

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * Returns a [PresenterEntry] scoped to [viewModelStoreOwner]. Presenters obtained here can use
 * `rememberSaveable` without accepting a `SavedStateHandle` themselves. The owner must provide the
 * saved-state creation extras supplied by an Activity, Fragment, or navigation entry.
 */
@Composable
@OptIn(MauzunViewModelAdapterApi::class)
public inline fun <reified VM : MauzunViewModel<*, *, *>> mauzunViewModel(
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
): PresenterEntry<VM> {
    requireUsableKey(key)
    return mauzunPresenterEntry(
        key = key,
        modelClass = VM::class.java,
        viewModelStoreOwner = viewModelStoreOwner,
        extras = extras,
    ) {
        androidxViewModel<VM>(
            viewModelStoreOwner = viewModelStoreOwner,
            key = key,
            factory = factory,
            extras = extras,
        )
    }
}

/**
 * Returns a [PresenterEntry] for a ViewModel built by [create] when [viewModelStoreOwner] holds
 * none. Use it to hand an assisted factory its runtime argument:
 *
 * ```
 * val presenter = mauzunViewModel<DetailViewModel> { detailFactory.create(gtin) }
 * ```
 *
 * An owner that already holds the ViewModel keeps it and [create] never runs, so a captured
 * argument only reaches a ViewModel created now. Give each argument's screen its own owner or
 * key, the way a Navigation 3 entry does.
 *
 * [create] receives the [CreationExtras], so `createSavedStateHandle()` works inside it.
 */
@Composable
public inline fun <reified VM : MauzunViewModel<*, *, *>> mauzunViewModel(
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
    noinline create: CreationExtras.() -> VM,
): PresenterEntry<VM> = mauzunViewModel(
    key = key,
    viewModelStoreOwner = viewModelStoreOwner,
    factory = viewModelFactory { initializer { create() } },
    extras = extras,
)

/**
 * Creates an entry for a ViewModel obtained by another integration.
 *
 * Adapters must obtain the ViewModel inside [viewModel] and pass the same owner, key, requested
 * [modelClass], and creation [extras] used by that integration. The requested class keys the
 * saved state, so a factory returning a subtype restores the same values either way. Calling
 * this from [MauzunViewModel.present] is rejected before [viewModel] runs.
 */
@Composable
@MauzunViewModelAdapterApi
public fun <VM : MauzunViewModel<*, *, *>> mauzunPresenterEntry(
    key: String?,
    modelClass: Class<VM>,
    viewModelStoreOwner: ViewModelStoreOwner,
    extras: CreationExtras,
    viewModel: @Composable () -> VM,
): PresenterEntry<VM> {
    check(!LocalPresenterComposition.current) {
        "mauzunViewModel() cannot be called from MauzunViewModel.present()"
    }
    requireUsableKey(key)
    val instance = viewModel()
    val attached = attachMauzunSavedState(
        viewModel = instance,
        key = key,
        modelClass = modelClass,
        viewModelStoreOwner = viewModelStoreOwner,
        extras = extras,
    )
    return remember(attached) { PresenterEntry.create(attached) }
}

// A presenter keyed under the holder prefix would replace another screen's holder, and that
// screen's saved state would be lost. This runs before anything touches the ViewModelStore.
// The hilt module carries a copy. This keeps the 0.9 package in its value because the prefix is
// persisted on devices and must survive the Mauzun rename.
@PublishedApi
internal fun requireUsableKey(key: String?) {
    require(key == null || !key.startsWith(SAVED_STATE_HOLDER_KEY_PREFIX)) {
        "ViewModel keys starting with $SAVED_STATE_HOLDER_KEY_PREFIX are reserved"
    }
}

private const val SAVED_STATE_HOLDER_KEY_PREFIX =
    "io.github.raheelnaz.molecule.presenter-saved-state-holder:"

private class PresenterSavedStateHolder(handle: SavedStateHandle) : ViewModel() {
    val savedState = PresenterSavedState(handle)
}

private val presenterSavedStateHolderFactory = viewModelFactory {
    initializer {
        // androidx throws IllegalArgumentException for a missing extra and IllegalStateException
        // for missing setup. Anything else is a bug and stays unwrapped.
        val handle = try {
            createSavedStateHandle()
        } catch (failure: IllegalArgumentException) {
            throw unsupportedOwner(failure)
        } catch (failure: IllegalStateException) {
            throw unsupportedOwner(failure)
        }
        PresenterSavedStateHolder(handle)
    }
}

@Composable
@PublishedApi
internal fun <VM : MauzunViewModel<*, *, *>> attachMauzunSavedState(
    viewModel: VM,
    key: String?,
    modelClass: Class<out VM>,
    viewModelStoreOwner: ViewModelStoreOwner,
    extras: CreationExtras,
): VM {
    requireUsableKey(key)
    // Derived and explicit keys live in separate namespaces, so they can never meet.
    val holderKey = SAVED_STATE_HOLDER_KEY_PREFIX + if (key != null) {
        "explicit:$key"
    } else {
        val canonicalName = modelClass.canonicalName
            ?: throw IllegalArgumentException("Local and anonymous classes can not be ViewModels")
        "default:$canonicalName"
    }
    val holder = androidxViewModel<PresenterSavedStateHolder>(
        viewModelStoreOwner = viewModelStoreOwner,
        key = holderKey,
        factory = presenterSavedStateHolderFactory,
        extras = extras,
    )
    viewModel.attachSavedState(holder.savedState)
    return viewModel
}

private fun unsupportedOwner(cause: RuntimeException) = IllegalStateException(
    "mauzunViewModel needs a ViewModelStoreOwner that provides saved state. " +
        "An Activity, Fragment, or navigation entry does; this owner does not.",
    cause,
)
