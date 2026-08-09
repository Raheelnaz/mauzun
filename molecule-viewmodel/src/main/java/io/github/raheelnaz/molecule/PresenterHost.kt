package io.github.raheelnaz.molecule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

/**
 * Renders [content] from [presenter] and handles effects while the lifecycle is at least
 * [effectsMinActiveState].
 */
@Composable
public fun <Event : Any, Model : Any, Effect : Any> PresenterHost(
    presenter: MoleculePresenter<Event, Model, Effect>,
    onEffect: suspend (Effect) -> Unit,
    effectsMinActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    content: @Composable (state: Model, onEvent: (Event) -> Unit) -> Unit,
) {
    require(effectsMinActiveState.isAtLeast(Lifecycle.State.CREATED)) {
        "effectsMinActiveState must be CREATED, STARTED, or RESUMED"
    }
    val state by presenter.state.collectAsStateWithLifecycle()
    val currentOnEffect by rememberUpdatedState(onEffect)
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(presenter, lifecycleOwner, effectsMinActiveState) {
        lifecycleOwner.repeatOnLifecycle(effectsMinActiveState) {
            presenter.effects.collect { currentOnEffect(it) }
        }
    }

    content(state, presenter::onEvent)
}
