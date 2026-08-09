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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** A ViewModel whose state is produced by a Molecule presenter. */
public abstract class MoleculeViewModel<Event : Any, Model : Any, Effect : Any> :
    ViewModel(), MoleculePresenter<Event, Model, Effect> {

    private val eventChannel = Channel<Event>(capacity = 50)
    private val effectChannel = redeliveringChannel<Effect>(capacity = 50)

    // Keep the same downstream capacity as shareIn's default buffer.
    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    final override val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    private var startAttempted = false
    private var startFailure: Throwable? = null

    // Immediate makes state.value available on the first read.
    final override val state: StateFlow<Model> by lazy {
        check(viewModelScope.isActive) { "state was first read after the ViewModel was cleared" }
        // Kotlin retries a lazy initializer after it throws.
        startFailure?.let {
            throw IllegalStateException("the presenter already failed to start", it)
        }
        // lazy's lock is reentrant: a state read inside present would recurse right back here.
        check(!startAttempted) { "the presenter is already starting" }
        startAttempted = true
        startPresenter()
    }

    // A presenter failure must stop the event pump too. The cancel on a failed start also
    // covers a Recomposer leak fixed upstream in cashapp/molecule#761.
    private fun startPresenter(): StateFlow<Model> {
        val presenterJob = Job(viewModelScope.coroutineContext.job)
        val presenterScope = CoroutineScope(viewModelScope.coroutineContext + presenterJob)
        try {
            val models = presenterScope.launchMolecule(
                mode = RecompositionMode.Immediate,
                context = Dispatchers.Main,
            ) {
                present(events)
            }
            // Main queues the pump until every initial event collector has subscribed.
            presenterScope.launch(Dispatchers.Main) {
                for (event in eventChannel) events.emit(event)
            }
            return models
        } catch (t: Throwable) {
            startFailure = t
            presenterJob.cancel()
            throw t
        }
    }

    /** Returns the current model for [events] and remembered presenter state. */
    @Composable
    public abstract fun present(events: Flow<Event>): Model

    /**
     * Collects [events] while the presenter is running.
     *
     * Call this unconditionally. The event stream does not replay items to a collector added after
     * startup.
     */
    @Composable
    protected fun CollectEvents(
        events: Flow<Event>,
        handler: suspend CoroutineScope.(Event) -> Unit,
    ) {
        val current by rememberUpdatedState(handler)
        LaunchedEffect(events) { events.collect { current(it) } }
    }

    /** Collects events of type [T]. This follows the same rules as [CollectEvents]. */
    @Composable
    protected inline fun <reified T : Event> CollectEventsOf(
        events: Flow<Event>,
        noinline handler: suspend CoroutineScope.(T) -> Unit,
    ) {
        val current by rememberUpdatedState(handler)
        LaunchedEffect(events) { events.filterIsInstance<T>().collect { current(it) } }
    }

    /**
     * Adds [event] to the input queue. Throws if its 50 slots are full. Events sent after the
     * ViewModel is cleared are ignored.
     */
    final override fun onEvent(event: Event) {
        eventChannel.trySendOrThrow(event, "Event", this)
    }

    /**
     * Adds [effect] to the effect queue. Throws if its 50 slots are full. Effects emitted after the
     * ViewModel is cleared are ignored.
     */
    protected fun emitEffect(effect: Effect) {
        effectChannel.trySendOrThrow(effect, "Effect", this)
    }

    /** Register subclass cleanup with [addCloseable]. */
    final override fun onCleared() {
        super.onCleared()
        eventChannel.close()
        effectChannel.close()
    }
}

private fun <T : Any> Channel<T>.trySendOrThrow(value: T, streamName: String, owner: Any) {
    val result = trySend(value)
    if (result.isClosed) return
    // Payload values may contain user data, so only include their types.
    check(result.isSuccess) {
        "$streamName buffer overflow in ${owner.typeName} (latest: ${value.typeName})"
    }
}

private val Any.typeName: String get() = this::class.simpleName ?: this::class.java.name

// Return an effect to the queue if its collector is cancelled before handling it.
private fun <T> redeliveringChannel(capacity: Int): Channel<T> {
    lateinit var channel: Channel<T>
    channel = Channel(capacity) { channel.trySend(it) }
    return channel
}
