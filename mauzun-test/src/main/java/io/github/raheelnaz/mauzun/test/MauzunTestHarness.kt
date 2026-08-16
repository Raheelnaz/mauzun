package io.github.raheelnaz.mauzun.test

import androidx.compose.runtime.withCompositionLocals
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.turbineScope
import io.github.raheelnaz.mauzun.MauzunViewModel
import io.github.raheelnaz.mauzun.MauzunViewModelTestingApi
import io.github.raheelnaz.mauzun.effectsForTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn

/**
 * Runs [MauzunViewModel.present] with a test event stream.
 *
 * [MauzunTestScope.sendEvent] finishes immediate presenter work before returning. Models are
 * distinct, and the test fails if a model or effect is left unconsumed. Send events through the
 * test scope rather than [MauzunViewModel.onEvent].
 */
@OptIn(MauzunViewModelTestingApi::class)
public suspend fun <Event : Any, Model : Any, Effect : Any> MauzunViewModel<Event, Model, Effect>.test(
    validate: suspend MauzunTestScope<Event, Model, Effect>.() -> Unit,
): Unit = turbineScope {
    val events = Channel<Event>(capacity = Channel.UNLIMITED)

    // Broadcast events like production while keeping sendEvent synchronous.
    val eventsJob = Job(currentCoroutineContext().job)
    val eventsScope = CoroutineScope(currentCoroutineContext() + eventsJob + Dispatchers.Unconfined)
    val eventsFlow = events.receiveAsFlow().shareIn(eventsScope, SharingStarted.Lazily)

    val effectsTurbine = effectsForTest().testIn(this)
    val lifecycleOwner = ResumedLifecycleOwner()
    val stateTurbine = moleculeFlow(RecompositionMode.Immediate) {
        withCompositionLocals(LocalLifecycleOwner provides lifecycleOwner) {
            present(eventsFlow)
        }
    }
        // Match StateFlow's equality-based conflation.
        .distinctUntilChanged()
        .testIn(this)

    try {
        MauzunTestScope(stateTurbine, effectsTurbine, events).validate()
        stateTurbine.ensureAllEventsConsumed()
        effectsTurbine.ensureAllEventsConsumed()
    } finally {
        stateTurbine.cancelAndIgnoreRemainingEvents()
        effectsTurbine.cancelAndIgnoreRemainingEvents()
        events.close()
        eventsJob.cancel()
    }
}

private class ResumedLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply {
        currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = registry
}

public class MauzunTestScope<Event : Any, Model : Any, Effect : Any> internal constructor(
    private val stateTurbine: ReceiveTurbine<Model>,
    private val effectsTurbine: ReceiveTurbine<Effect>,
    private val events: Channel<Event>,
) {
    /** Waits for the next distinct model. */
    public suspend fun awaitState(): Model = stateTurbine.awaitItem()

    /** Skips the next [count] distinct models. */
    public suspend fun skipStates(count: Int): Unit = stateTurbine.skipItems(count)

    /** Fails if a model is ready now. This does not wait for future work. */
    public fun expectNoStateChanges(): Unit = stateTurbine.expectNoEvents()

    /** Sends [event] and finishes presenter work that does not suspend or change dispatchers. */
    public fun sendEvent(event: Event) {
        events.trySend(event).getOrThrow()
    }

    /** Waits for the next effect. */
    public suspend fun awaitEffect(): Effect = effectsTurbine.awaitItem()

    /** Fails if an effect is ready now. This does not wait for future work. */
    public fun expectNoEffects(): Unit = effectsTurbine.expectNoEvents()

    /** Waits for the failure that ended the presenter. */
    public suspend fun awaitFailure(): Throwable = stateTurbine.awaitError()
}
