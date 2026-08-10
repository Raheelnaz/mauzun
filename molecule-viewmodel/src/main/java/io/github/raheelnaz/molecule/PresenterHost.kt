package io.github.raheelnaz.molecule

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle

/**
 * Renders [content] from [presenter] and handles effects while the lifecycle is at least
 * [effectsMinActiveState].
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
    content: @Composable (state: Model, onEvent: (Event) -> Unit) -> Unit,
): Unit = PresenterHost(presenter.binding, onEffect, effectsMinActiveState, content)
