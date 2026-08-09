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

/** A ViewModel backed by a Molecule presenter. Implement [present] to produce the screen model. */
public abstract class MoleculeViewModel<Event : Any, Model : Any, Effect : Any> :
    ViewModel(), MoleculePresenter<Event, Model, Effect> {

    private val eventChannel = Channel<Event>(capacity = 50)
    private val effectChannel = redeliveringChannel<Effect>(capacity = 50)

    // 64 is the buffer shareIn was using before the pump became explicit.
    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    final override val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    private var startAttempted = false
    private var startFailure: Throwable? = null

    // Immediate produces the first model synchronously and does not wait for display frames.
    final override val state: StateFlow<Model> by lazy {
        check(viewModelScope.isActive) { "state was first read after the ViewModel was cleared" }
        // lazy reruns an initializer that threw, which would compose a second presenter.
        startFailure?.let {
            throw IllegalStateException("the presenter already failed to start", it)
        }
        // lazy's lock is reentrant: a state read inside present would recurse right back here.
        check(!startAttempted) { "the presenter is already starting" }
        startAttempted = true
        startPresenter()
    }

    // One job owns the presenter runtime. A throw in the first composition cancels the
    // Recomposer it left behind (molecule#761); a crash later takes the event pump down too.
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
            // Main queues the pump behind the collectors the composition just posted.
            // Main.immediate would run it inline and beat all but the first collector.
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

    /** Produces a model from snapshot state and [events]. */
    @Composable
    public abstract fun present(events: Flow<Event>): Model

    /**
     * Collects [events] for the lifetime of the presenter. Call this unconditionally. Events sent
     * before the presenter starts are kept; events sent while no collector is subscribed are
     * dropped.
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

    /**
     * Throws when the 50 slot event queue fills. A started presenter buffers 64 more past it.
     * A no-op once the ViewModel is cleared.
     */
    final override fun onEvent(event: Event) {
        eventChannel.trySendOrThrow(event, "Event", this)
    }

    /** Throws after 50 unconsumed effects. A no-op once the ViewModel is cleared. */
    protected fun emitEffect(effect: Effect) {
        effectChannel.trySendOrThrow(effect, "Effect", this)
    }

    /** Use [addCloseable] for subclass cleanup. */
    final override fun onCleared() {
        super.onCleared()
        eventChannel.close()
        effectChannel.close()
    }
}

private fun <T : Any> Channel<T>.trySendOrThrow(value: T, streamName: String, owner: Any) {
    val result = trySend(value)
    if (result.isClosed) return
    // Do not include the payload in the error; events and effects may contain user data.
    check(result.isSuccess) {
        "$streamName buffer overflow in ${owner.typeName} (latest: ${value.typeName})"
    }
}

// simpleName is null for anonymous classes.
private val Any.typeName: String get() = this::class.simpleName ?: this::class.java.name

// A cancelled receive puts the effect back instead of dropping it.
private fun <T> redeliveringChannel(capacity: Int): Channel<T> {
    lateinit var channel: Channel<T>
    channel = Channel(capacity) { channel.trySend(it) }
    return channel
}
