package io.github.raheelnaz.molecule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Renders [content] from [presenter] and handles effects while the lifecycle is at least
 * [effectsMinActiveState].
 *
 * The host lifecycle also drives the presenter's, so lifecycle aware collection inside
 * `present` pauses while the screen is stopped or covered. Set [drivePresenterLifecycle]
 * to false to keep the presenter resumed for background work.
 */
@Composable
public fun <
    Event : Any,
    Model : Any,
    Effect : Any,
    VM : MoleculeViewModel<Event, Model, Effect>,
> PresenterHost(
    presenter: PresenterEntry<VM>,
    onEffect: suspend (Effect) -> Unit,
    effectsMinActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    drivePresenterLifecycle: Boolean = true,
    content: @Composable (state: Model, onEvent: (Event) -> Unit) -> Unit,
) {
    if (drivePresenterLifecycle) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(presenter.viewModel, lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                // DESTROYED is terminal for a registry and the ViewModel outlives the host,
                // so a dying host parks the presenter at CREATED instead.
                presenter.viewModel.movePresenterToState(
                    if (event.targetState == Lifecycle.State.DESTROYED) {
                        Lifecycle.State.CREATED
                    } else {
                        event.targetState
                    },
                )
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                // Leaving composition means the screen is covered or gone.
                presenter.viewModel.movePresenterToState(Lifecycle.State.CREATED)
            }
        }
    }
    PresenterHost(presenter.binding, onEffect, effectsMinActiveState, content)
}
