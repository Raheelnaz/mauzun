package io.github.raheelnaz.molecule.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.currentComposer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.turbineScope
import io.github.raheelnaz.molecule.MoleculeViewModel
import io.github.raheelnaz.molecule.MoleculeViewModelTestingApi
import io.github.raheelnaz.molecule.effectsForTest
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
 * Runs [MoleculeViewModel.present] with a test event stream.
 *
 * [MoleculeTestScope.sendEvent] finishes immediate presenter work before returning. Models are
 * distinct, and the test fails if a model or effect is left unconsumed. Send events through the
 * test scope rather than [MoleculeViewModel.onEvent].
 *
 * The presenter sees a lifecycle that starts at [initialLifecycleState] and moves with
 * [MoleculeTestScope.moveToState].
 */
@OptIn(MoleculeViewModelTestingApi::class)
public suspend fun <Event : Any, Model : Any, Effect : Any> MoleculeViewModel<Event, Model, Effect>.test(
    initialLifecycleState: Lifecycle.State = Lifecycle.State.RESUMED,
    validate: suspend MoleculeTestScope<Event, Model, Effect>.() -> Unit,
): Unit = turbineScope {
    val events = Channel<Event>(capacity = Channel.UNLIMITED)

    // Broadcast events like production while keeping sendEvent synchronous.
    val eventsJob = Job(currentCoroutineContext().job)
    val eventsScope = CoroutineScope(currentCoroutineContext() + eventsJob + Dispatchers.Unconfined)
    val eventsFlow = events.receiveAsFlow().shareIn(eventsScope, SharingStarted.Lazily)

    val lifecycleOwner = HarnessLifecycleOwner(initialLifecycleState)
    val effectsTurbine = effectsForTest().testIn(this)
    val stateTurbine = moleculeFlow(RecompositionMode.Immediate) {
        withLifecycleOwner(lifecycleOwner) { present(eventsFlow) }
    }
        // Match StateFlow's equality-based conflation.
        .distinctUntilChanged()
        .testIn(this)

    try {
        MoleculeTestScope(stateTurbine, effectsTurbine, events, lifecycleOwner).validate()
        stateTurbine.ensureAllEventsConsumed()
        effectsTurbine.ensureAllEventsConsumed()
    } finally {
        stateTurbine.cancelAndIgnoreRemainingEvents()
        effectsTurbine.cancelAndIgnoreRemainingEvents()
        events.close()
        eventsJob.cancel()
    }
}

public class MoleculeTestScope<Event : Any, Model : Any, Effect : Any> internal constructor(
    private val stateTurbine: ReceiveTurbine<Model>,
    private val effectsTurbine: ReceiveTurbine<Effect>,
    private val events: Channel<Event>,
    private val lifecycleOwner: HarnessLifecycleOwner,
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

    /** Moves the presenter's lifecycle, like a host screen being covered or coming back. */
    public fun moveToState(next: Lifecycle.State) {
        lifecycleOwner.moveToState(next)
    }
}

// createUnsafe skips main thread enforcement, which would fail JVM tests.
internal class HarnessLifecycleOwner(initialState: Lifecycle.State) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
        .apply { currentState = initialState }

    override val lifecycle: Lifecycle get() = registry

    fun moveToState(next: Lifecycle.State) {
        registry.currentState = next
    }
}

// CompositionLocalProvider returns Unit, and the molecule needs the model back.
@Composable
@OptIn(InternalComposeApi::class)
private fun <T> withLifecycleOwner(owner: LifecycleOwner, content: @Composable () -> T): T {
    val composer = currentComposer
    composer.startProviders(arrayOf(LocalLifecycleOwner provides owner))
    val result = content()
    composer.endProviders()
    return result
}
