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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn

/**
 * A ViewModel whose logic is a @Composable function.
 *
 * Implement [present]. Events sent with [onEvent] are buffered until the presenter collects
 * them, then broadcast to every collector inside it. Effects are buffered while the UI is not
 * collecting and delivered exactly once.
 */
public abstract class MoleculeViewModel<Event : Any, Model : Any, Effect : Any> :
    ViewModel(), MoleculePresenter<Event, Model, Effect> {

    private val eventChannel = Channel<Event>(capacity = 50)
    private val effectChannel = Channel<Effect>(capacity = 50)

    // The presenter reads its `events` parameter, never this field, so tests can substitute
    // their own stream. Lazily: events wait in the channel until the presenter collects.
    private val events: Flow<Event> by lazy {
        eventChannel.receiveAsFlow().shareIn(viewModelScope, SharingStarted.Lazily)
    }

    final override val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    // Immediate brings its own frame clock: a synchronous first composition so state always has
    // a value, then recomposition when data changes rather than on display frames.
    final override val state: StateFlow<Model> by lazy {
        viewModelScope.launchMolecule(
            mode = RecompositionMode.Immediate,
            context = Dispatchers.Main,
        ) {
            present(events)
        }
    }

    /**
     * The presenter: events in, model out.
     *
     * Collect [events] with [CollectEvents], and write state from handlers rather than the
     * composition body. The README covers the reasoning behind both.
     *
     * Public because @Composable already restricts callers: only the molecule started by [state]
     * or a test harness can invoke it.
     */
    @Composable
    public abstract fun present(events: Flow<Event>): Model

    /**
     * Collects [events] for the lifetime of the composition and hands each one to [handler].
     * Call it unconditionally: a collector behind an `if` drops events while the branch is off.
     * The handler runs in the collector's scope, so it can launch work that outlives a single
     * event.
     */
    @Composable
    protected fun CollectEvents(
        events: Flow<Event>,
        handler: suspend CoroutineScope.(Event) -> Unit,
    ) {
        val current by rememberUpdatedState(handler)
        LaunchedEffect(events) { events.collect { current(it) } }
    }

    /** Throws after 50 unconsumed events. A no-op once the ViewModel is cleared. */
    final override fun onEvent(event: Event) {
        eventChannel.trySendOrThrow(event, "Event")
    }

    /** Call from event handlers and effects, not the composition body. */
    protected fun emitEffect(effect: Effect) {
        effectChannel.trySendOrThrow(effect, "Effect")
    }

    /** Final so channel cleanup cannot be lost. Use [addCloseable] for subclass teardown. */
    final override fun onCleared() {
        super.onCleared()
        eventChannel.close()
        effectChannel.close()
    }
}

private fun <T : Any> Channel<T>.trySendOrThrow(value: T, streamName: String) {
    val result = trySend(value)
    // Closed just means the ViewModel was cleared. A full buffer is a wedged or spammed
    // presenter, so fail loudly. Class name only: payloads may hold user data.
    if (result.isClosed) return
    check(result.isSuccess) { "$streamName buffer overflow (latest: ${value::class.simpleName})" }
}
