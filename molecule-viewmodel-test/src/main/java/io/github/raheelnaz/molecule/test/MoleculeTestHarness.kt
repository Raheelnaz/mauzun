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
 * Drives [MoleculeViewModel.present] with a test-owned event stream.
 *
 * [MoleculeTestScope.sendEvent] runs the event's entire cascade before returning, so the next
 * line can assert. [MoleculeTestScope.awaitState] yields distinct models, matching what the UI
 * receives from the StateFlow in production. Effects are the ViewModel's real effects. States
 * and effects left unasserted when the block ends fail the test.
 *
 * Use sendEvent, not [MoleculeViewModel.onEvent]. The production channel is not connected here.
 */
public suspend fun <Event : Any, Model : Any, Effect : Any> MoleculeViewModel<Event, Model, Effect>.test(
    validate: suspend MoleculeTestScope<Event, Model, Effect>.() -> Unit,
): Unit = turbineScope {
    val events = Channel<Event>(capacity = Channel.UNLIMITED)

    // A hot share like production, so multiple collectors in a presenter all see every event.
    // Unconfined keeps the chain from trySend through recomposition inline, which is what lets
    // sendEvent complete its cascade before returning. The Job lets the finally block cancel
    // the share, which never completes on its own and would otherwise keep turbineScope waiting.
    val eventsScope = CoroutineScope(currentCoroutineContext() + Job() + Dispatchers.Unconfined)
    val eventsFlow = events.receiveAsFlow().shareIn(eventsScope, SharingStarted.Lazily)

    val effectsTurbine = effects.testIn(this)
    val stateTurbine = moleculeFlow(RecompositionMode.Immediate) { present(eventsFlow) }
        // Match the StateFlow in production, which drops values equal to the current one.
        .distinctUntilChanged()
        .testIn(this)

    try {
        MoleculeTestScope(stateTurbine, effectsTurbine, events).validate()
        // Only on success, so it cannot mask the real failure.
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

    /**
     * Skips [count] distinct models. Count-based on purpose: a predicate that never matches
     * would hang the test instead of failing it.
     */
    public suspend fun skipStates(count: Int): Unit = stateTurbine.skipItems(count)

    /** Valid right after [sendEvent]. Only delayed or re-dispatched work is not yet visible. */
    public fun expectNoStateChanges(): Unit = stateTurbine.expectNoEvents()

    public fun sendEvent(event: Event) {
        events.trySend(event)
    }

    public suspend fun awaitEffect(): Effect = effectsTurbine.awaitItem()

    public fun expectNoEffects(): Unit = effectsTurbine.expectNoEvents()
}
