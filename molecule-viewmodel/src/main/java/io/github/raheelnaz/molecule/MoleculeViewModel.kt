package io.github.raheelnaz.molecule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive

/** A ViewModel backed by a Molecule presenter. Implement [present] to produce the screen model. */
public abstract class MoleculeViewModel<Event : Any, Model : Any, Effect : Any> :
    ViewModel(), MoleculePresenter<Event, Model, Effect> {

    private val eventChannel = Channel<Event>(capacity = 50)
    private val effectChannel = redeliveringChannel<Effect>(capacity = 50)

    // Lazy sharing keeps early events in the channel and lets tests supply a different stream.
    private val events: Flow<Event> by lazy {
        eventChannel.receiveAsFlow().shareIn(viewModelScope, SharingStarted.Lazily)
    }

    final override val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    // Immediate produces the first model synchronously and does not wait for display frames.
    final override val state: StateFlow<Model> by lazy {
        check(viewModelScope.isActive) { "state was first read after the ViewModel was cleared" }
        viewModelScope.launchMolecule(
            mode = RecompositionMode.Immediate,
            context = Dispatchers.Main,
        ) {
            present(events)
        }
    }

    /** Produces a model from snapshot state and [events]. */
    @Composable
    public abstract fun present(events: Flow<Event>): Model

    /**
     * Collects [events] for the lifetime of the presenter. Call this unconditionally; events are
     * lost whenever no collector is active.
     */
    @Composable
    protected fun CollectEvents(
        events: Flow<Event>,
        handler: suspend CoroutineScope.(Event) -> Unit,
    ) {
        val current by rememberUpdatedState(handler)
        LaunchedEffect(events) { events.collect { current(it) } }
    }

    /** Collects events of type [T] for the lifetime of the presenter. */
    @Composable
    protected inline fun <reified T : Event> CollectEventsOf(
        events: Flow<Event>,
        noinline handler: suspend CoroutineScope.(T) -> Unit,
    ) {
        val current by rememberUpdatedState(handler)
        LaunchedEffect(events) { events.filterIsInstance<T>().collect { current(it) } }
    }

    /** Throws after 50 unconsumed events. A no-op once the ViewModel is cleared. */
    final override fun onEvent(event: Event) {
        eventChannel.trySendOrThrow(event, "Event")
    }

    /** Throws after 50 unconsumed effects. A no-op once the ViewModel is cleared. */
    protected fun emitEffect(effect: Effect) {
        effectChannel.trySendOrThrow(effect, "Effect")
    }

    /** Use [addCloseable] for subclass cleanup. */
    final override fun onCleared() {
        super.onCleared()
        eventChannel.close()
        effectChannel.close()
    }
}

private fun <T : Any> Channel<T>.trySendOrThrow(value: T, streamName: String) {
    val result = trySend(value)
    if (result.isClosed) return
    // Do not include the payload in the error; events and effects may contain user data.
    check(result.isSuccess) { "$streamName buffer overflow (latest: ${value::class.simpleName})" }
}

// A cancelled receive puts the effect back instead of dropping it.
private fun <T> redeliveringChannel(capacity: Int): Channel<T> {
    lateinit var channel: Channel<T>
    channel = Channel(capacity) { channel.trySend(it) }
    return channel
}
