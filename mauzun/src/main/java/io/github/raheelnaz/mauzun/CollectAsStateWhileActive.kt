package io.github.raheelnaz.mauzun

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Collects this Flow while the current lifecycle is at least [minActiveState] and cancels the
 * subscription below it. When collection resumes, a cold Flow starts again and a hot Flow
 * resubscribes.
 *
 * Collection does not switch to `Dispatchers.Main`, so a presenter using it runs in the test
 * harness without `Dispatchers.setMain`.
 */
@Composable
public fun <T> Flow<T>.collectAsStateWhileActive(
    initialValue: T,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
): State<T> {
    require(minActiveState >= Lifecycle.State.CREATED) {
        "minActiveState must be CREATED, STARTED, or RESUMED"
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(initialValue, this, lifecycle, minActiveState) {
        lifecycle.currentStateFlow
            .map { it.isAtLeast(minActiveState) }
            .distinctUntilChanged()
            .collectLatest { active ->
                if (active) collect { value = it }
            }
    }
}

/** Collects this StateFlow starting from its current value. See the Flow overload. */
// The value read seeds the initial state. Later values arrive through collection.
@Suppress("StateFlowValueCalledInComposition")
@Composable
public fun <T> StateFlow<T>.collectAsStateWhileActive(
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
): State<T> = collectAsStateWhileActive(initialValue = value, minActiveState = minActiveState)
