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
 * Hosts a [MoleculePresenter]: collects state and delivers effects while the screen is at least
 * STARTED, so a stopped screen buffers effects instead of acting on them while invisible.
 *
 * Navigation 2 and 3 both hold a destination at STARTED while a transition animates, so effects
 * can fire mid-animation. Pass [effectsMinActiveState] = RESUMED if an effect that mutates the
 * back stack during a transition is a problem. RESUMED means the transition has settled.
 */
@Composable
public fun <Event : Any, Model : Any, Effect : Any> UiFactory(
    presenter: MoleculePresenter<Event, Model, Effect>,
    onEffect: suspend (Effect) -> Unit,
    effectsMinActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    content: @Composable (state: Model, onEvent: (Event) -> Unit) -> Unit,
) {
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
