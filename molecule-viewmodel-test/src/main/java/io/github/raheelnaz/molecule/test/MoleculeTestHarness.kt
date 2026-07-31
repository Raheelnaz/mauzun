package io.github.raheelnaz.molecule.test

import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.turbineScope
import io.github.raheelnaz.molecule.MoleculeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn

/**
 * Runs [MoleculeViewModel.present] with a test event stream. [MoleculeTestScope.sendEvent] runs
 * immediate work synchronously. Models are distinct, and unasserted models or effects fail the
 * test.
 *
 * Use [MoleculeTestScope.sendEvent], not [MoleculeViewModel.onEvent].
 */
public suspend fun <Event : Any, Model : Any, Effect : Any> MoleculeViewModel<Event, Model, Effect>.test(
    validate: suspend MoleculeTestScope<Event, Model, Effect>.() -> Unit,
): Unit = turbineScope {
    val events = Channel<Event>(capacity = Channel.UNLIMITED)

    // Match production's broadcast behavior. Unconfined keeps sendEvent synchronous, and the
    // extra Job gives finally something to cancel when the test ends.
    val eventsScope = CoroutineScope(currentCoroutineContext() + Job() + Dispatchers.Unconfined)
    val eventsFlow = events.receiveAsFlow().shareIn(eventsScope, SharingStarted.Lazily)

    val effectsTurbine = effects.testIn(this)
    val stateTurbine = moleculeFlow(RecompositionMode.Immediate) { present(eventsFlow) }
        // StateFlow does not emit a value equal to its current value.
        .distinctUntilChanged()
        .testIn(this)

    try {
        MoleculeTestScope(stateTurbine, effectsTurbine, events).validate()
        stateTurbine.ensureAllEventsConsumed()
        effectsTurbine.ensureAllEventsConsumed()
    } finally {
        stateTurbine.cancelAndIgnoreRemainingEvents()
        effectsTurbine.cancelAndIgnoreRemainingEvents()
        eventsScope.cancel()
    }
}

public class MoleculeTestScope<Event : Any, Model : Any, Effect : Any> internal constructor(
    private val stateTurbine: ReceiveTurbine<Model>,
    private val effectsTurbine: ReceiveTurbine<Effect>,
    private val events: Channel<Event>,
) {
    /** The next distinct model. */
    public suspend fun awaitState(): Model = stateTurbine.awaitItem()

    /** Skips the next [count] distinct models. */
    public suspend fun skipStates(count: Int): Unit = stateTurbine.skipItems(count)

    /** Checks for an immediate model change after [sendEvent]. */
    public fun expectNoStateChanges(): Unit = stateTurbine.expectNoEvents()

    public fun sendEvent(event: Event) {
        events.trySend(event)
    }

    public suspend fun awaitEffect(): Effect = effectsTurbine.awaitItem()

    public fun expectNoEffects(): Unit = effectsTurbine.expectNoEvents()
}
