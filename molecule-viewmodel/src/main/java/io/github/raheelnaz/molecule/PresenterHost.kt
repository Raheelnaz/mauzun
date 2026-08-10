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
 * Renders [content] from [viewModel]'s binding and handles effects while the lifecycle is at
 * least [effectsMinActiveState].
 */
@Composable
public fun <Event : Any, Model : Any, Effect : Any> PresenterHost(
    viewModel: MoleculeViewModel<Event, Model, Effect>,
    onEffect: suspend (Effect) -> Unit,
    effectsMinActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    content: @Composable (state: Model, onEvent: (Event) -> Unit) -> Unit,
): Unit = PresenterHost(viewModel.presenterBinding, onEffect, effectsMinActiveState, content)

/**
 * Renders [content] from [binding] and handles effects while the lifecycle is at least
 * [effectsMinActiveState]. Use this from a module that only knows the contract, or with a fake
 * binding in a test.
 */
@Composable
public fun <Event : Any, Model : Any, Effect : Any> PresenterHost(
    binding: PresenterBinding<Event, Model, Effect>,
    onEffect: suspend (Effect) -> Unit,
    effectsMinActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    content: @Composable (state: Model, onEvent: (Event) -> Unit) -> Unit,
) {
    require(effectsMinActiveState.isAtLeast(Lifecycle.State.CREATED)) {
        "effectsMinActiveState must be CREATED, STARTED, or RESUMED"
    }
    val state by binding.state.collectAsStateWithLifecycle()
    val currentOnEffect by rememberUpdatedState(onEffect)
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(binding, lifecycleOwner, effectsMinActiveState) {
        lifecycleOwner.repeatOnLifecycle(effectsMinActiveState) {
            binding.effects.collect { currentOnEffect(it) }
        }
    }

    content(state, binding::onEvent)
}
