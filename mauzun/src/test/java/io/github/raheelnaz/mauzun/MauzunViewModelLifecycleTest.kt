package io.github.raheelnaz.mauzun

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withCompositionLocals
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class MutableLifecycleOwner(
    initialState: Lifecycle.State,
) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply {
        currentState = initialState
    }

    override val lifecycle: Lifecycle get() = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

private class LifecycleHost(
    initialState: Lifecycle.State,
    restoredState: Bundle? = null,
    override val viewModelStore: ViewModelStore = ViewModelStore(),
) : LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory {
    private val registry = LifecycleRegistry.createUnsafe(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        ViewModelProvider.NewInstanceFactory()
    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras().apply {
            set(SAVED_STATE_REGISTRY_OWNER_KEY, this@LifecycleHost)
            set(VIEW_MODEL_STORE_OWNER_KEY, this@LifecycleHost)
        }

    init {
        enableSavedStateHandles()
        controller.performRestore(restoredState)
        registry.currentState = initialState
    }

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }

    fun save(): Bundle = Bundle().also(controller::performSave)
}

private class TrackedFlow(initialValue: Int = 0) {
    private val values = MutableSharedFlow<Int>(replay = 1).apply {
        tryEmit(initialValue)
    }

    var activeCollectors: Int = 0
        private set

    val flow: Flow<Int> = flow {
        activeCollectors++
        try {
            values.collect { emit(it) }
        } finally {
            activeCollectors--
        }
    }

    fun emit(value: Int) {
        check(values.tryEmit(value))
    }
}

private data object Increment

private data class LifecycleModel(
    val lifecycleValue: Int,
    val plainValue: Int,
    val remembered: Int,
    val saveable: Int,
    val launches: Int,
)

private class LifecycleViewModel(
    val lifecycleFlow: TrackedFlow,
    val plainFlow: TrackedFlow,
) : MauzunViewModel<Increment, LifecycleModel, String>() {
    var presenterStarts = 0
        private set

    @Composable
    override fun present(events: Flow<Increment>): LifecycleModel {
        remember {
            presenterStarts++
            Any()
        }
        var remembered by remember { mutableIntStateOf(0) }
        var saveable by rememberSaveable { mutableIntStateOf(0) }
        var launches by remember { mutableIntStateOf(0) }
        val lifecycleValue by lifecycleFlow.flow.collectAsStateWithLifecycle(initialValue = -1)
        val plainValue by plainFlow.flow.collectAsState(initial = -1)

        LaunchedEffect(Unit) { launches++ }
        CollectEvents(events) {
            remembered++
            saveable++
            emitEffect("handled-$remembered")
        }

        return LifecycleModel(
            lifecycleValue = lifecycleValue,
            plainValue = plainValue,
            remembered = remembered,
            saveable = saveable,
            launches = launches,
        )
    }
}

private data class LifecycleStressModel(
    val upstreamValue: Int,
    val eventsHandled: Int,
    val launches: Int,
)

private class LifecycleStressViewModel(
    val upstream: TrackedFlow,
) : MauzunViewModel<Increment, LifecycleStressModel, Nothing>() {
    var presenterStarts = 0
        private set

    @Composable
    override fun present(events: Flow<Increment>): LifecycleStressModel {
        remember {
            presenterStarts++
            Any()
        }
        var eventsHandled by remember { mutableIntStateOf(0) }
        var launches by remember { mutableIntStateOf(0) }
        val upstreamValue by upstream.flow.collectAsStateWithLifecycle(initialValue = -1)

        LaunchedEffect(Unit) { launches++ }
        CollectEvents(events) { eventsHandled++ }

        return LifecycleStressModel(upstreamValue, eventsHandled, launches)
    }
}

private class ResumedViewModel(
    val upstream: TrackedFlow,
) : MauzunViewModel<Nothing, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Nothing>): Int {
        val value by upstream.flow.collectAsStateWithLifecycle(
            initialValue = -1,
            minActiveState = Lifecycle.State.RESUMED,
        )
        return value
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private inline fun <reified VM : MauzunViewModel<*, *, *>> TestScope.entrySession(
    host: LifecycleHost,
    key: String? = null,
    noinline create: () -> VM,
): ReceiveChannel<PresenterEntry<VM>> {
    val factory = viewModelFactory { initializer { create() } }
    return moleculeFlow(RecompositionMode.Immediate) {
        withCompositionLocals(LocalLifecycleOwner provides host) {
            mauzunViewModel<VM>(
                key = key,
                viewModelStoreOwner = host,
                factory = factory,
            )
        }
    }.produceIn(backgroundScope)
}

class PresenterLifecycleOwnerTest {
    @Test
    fun `the most active attached lifecycle wins`() {
        val presenter = PresenterLifecycleOwner()
        val first = MutableLifecycleOwner(Lifecycle.State.CREATED)
        val second = MutableLifecycleOwner(Lifecycle.State.STARTED)
        val releaseFirst = presenter.attach(first.lifecycle)
        val releaseSecond = presenter.attach(second.lifecycle)

        assertThat(presenter.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)

        first.moveTo(Lifecycle.State.RESUMED)
        assertThat(presenter.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)

        releaseFirst()
        assertThat(presenter.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)

        releaseSecond()
        assertThat(presenter.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun `attaching one lifecycle twice is reference counted`() {
        val presenter = PresenterLifecycleOwner()
        val source = MutableLifecycleOwner(Lifecycle.State.RESUMED)
        val firstRelease = presenter.attach(source.lifecycle)
        val secondRelease = presenter.attach(source.lifecycle)

        firstRelease()
        firstRelease()
        assertThat(presenter.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)

        secondRelease()
        assertThat(presenter.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)

        source.moveTo(Lifecycle.State.STARTED)
        assertThat(presenter.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun `destroyed sources are removed and clear is terminal`() {
        val presenter = PresenterLifecycleOwner()
        val source = MutableLifecycleOwner(Lifecycle.State.RESUMED)
        presenter.attach(source.lifecycle)

        source.moveTo(Lifecycle.State.DESTROYED)
        assertThat(presenter.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)

        val other = MutableLifecycleOwner(Lifecycle.State.STARTED)
        presenter.attach(other.lifecycle)
        presenter.clear()
        assertThat(presenter.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)

        val late = MutableLifecycleOwner(Lifecycle.State.RESUMED)
        presenter.attach(late.lifecycle)
        other.moveTo(Lifecycle.State.RESUMED)
        assertThat(presenter.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MauzunViewModelLifecycleTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `retrieval attaches lifecycle without starting the presenter`() = runTest(dispatcher) {
        val host = LifecycleHost(Lifecycle.State.RESUMED)
        val lifecycleFlow = TrackedFlow()
        val plainFlow = TrackedFlow()
        lateinit var viewModel: LifecycleViewModel
        val session = entrySession(host) {
            LifecycleViewModel(lifecycleFlow, plainFlow).also { viewModel = it }
        }
        val entry = session.receive()
        runCurrent()

        assertThat(viewModel.presenterStarts).isEqualTo(0)
        assertThat(lifecycleFlow.activeCollectors).isEqualTo(0)

        entry.binding.state
        runCurrent()

        assertThat(viewModel.presenterStarts).isEqualTo(1)
        assertThat(lifecycleFlow.activeCollectors).isEqualTo(1)
        assertThat(plainFlow.activeCollectors).isEqualTo(1)
        session.cancel()
        host.viewModelStore.clear()
    }

    @Test
    fun `only lifecycle aware work pauses while presenter state and events survive`() =
        runTest(dispatcher) {
            val host = LifecycleHost(Lifecycle.State.RESUMED)
            val lifecycleFlow = TrackedFlow()
            val plainFlow = TrackedFlow()
            lateinit var viewModel: LifecycleViewModel
            val session = entrySession(host) {
                LifecycleViewModel(lifecycleFlow, plainFlow).also { viewModel = it }
            }
            val entry = session.receive()
            val state = entry.binding.state
            runCurrent()

            entry.binding.onEvent(Increment)
            advanceUntilIdle()
            assertThat(state.value.remembered).isEqualTo(1)
            lifecycleFlow.emit(1)
            plainFlow.emit(1)
            advanceUntilIdle()
            assertThat(state.value.lifecycleValue).isEqualTo(1)
            assertThat(state.value.plainValue).isEqualTo(1)

            host.moveTo(Lifecycle.State.CREATED)
            runCurrent()
            assertThat(lifecycleFlow.activeCollectors).isEqualTo(0)
            assertThat(plainFlow.activeCollectors).isEqualTo(1)
            assertThat(state.value.remembered).isEqualTo(1)

            lifecycleFlow.emit(2)
            plainFlow.emit(2)
            entry.binding.onEvent(Increment)
            advanceUntilIdle()

            assertThat(entry.binding.effects.take(2).toList())
                .containsExactly("handled-1", "handled-2")

            assertThat(state.value).isEqualTo(
                LifecycleModel(
                    lifecycleValue = 1,
                    plainValue = 2,
                    remembered = 2,
                    saveable = 2,
                    launches = 1,
                ),
            )
            assertThat(viewModel.presenterStarts).isEqualTo(1)

            host.moveTo(Lifecycle.State.STARTED)
            advanceUntilIdle()

            assertThat(lifecycleFlow.activeCollectors).isEqualTo(1)
            assertThat(state.value).isEqualTo(
                LifecycleModel(
                    lifecycleValue = 2,
                    plainValue = 2,
                    remembered = 2,
                    saveable = 2,
                    launches = 1,
                ),
            )
            assertThat(viewModel.presenterStarts).isEqualTo(1)
            session.cancel()
            host.viewModelStore.clear()
        }

    @Test
    fun `repeated lifecycle transitions do not restart the presenter or lose events`() =
        runTest(dispatcher) {
            val host = LifecycleHost(Lifecycle.State.RESUMED)
            val upstream = TrackedFlow()
            lateinit var viewModel: LifecycleStressViewModel
            val session = entrySession(host) {
                LifecycleStressViewModel(upstream).also { viewModel = it }
            }
            val entry = session.receive()
            val state = entry.binding.state
            advanceUntilIdle()

            repeat(100) { index ->
                host.moveTo(Lifecycle.State.CREATED)
                runCurrent()
                assertThat(upstream.activeCollectors).isEqualTo(0)

                entry.binding.onEvent(Increment)
                upstream.emit(index + 1)
                advanceUntilIdle()

                host.moveTo(Lifecycle.State.RESUMED)
                advanceUntilIdle()
                assertThat(upstream.activeCollectors).isEqualTo(1)
                assertThat(state.value.upstreamValue).isEqualTo(index + 1)
            }

            assertThat(state.value.eventsHandled).isEqualTo(100)
            assertThat(state.value.launches).isEqualTo(1)
            assertThat(viewModel.presenterStarts).isEqualTo(1)
            session.cancel()
            host.viewModelStore.clear()
        }

    @Test
    fun `resumed collection distinguishes started from resumed`() = runTest(dispatcher) {
        val host = LifecycleHost(Lifecycle.State.STARTED)
        val upstream = TrackedFlow()
        val session = entrySession(host) { ResumedViewModel(upstream) }
        val entry = session.receive()
        entry.binding.state
        runCurrent()

        assertThat(upstream.activeCollectors).isEqualTo(0)

        host.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(1)

        host.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(0)
        session.cancel()
        host.viewModelStore.clear()
    }

    @Test
    fun `multiple hosts keep work active while any host is resumed`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val firstHost = LifecycleHost(Lifecycle.State.RESUMED, viewModelStore = store)
        val secondHost = LifecycleHost(Lifecycle.State.CREATED, viewModelStore = store)
        val upstream = TrackedFlow()
        val firstSession = entrySession(firstHost) { ResumedViewModel(upstream) }
        val first = firstSession.receive()
        first.binding.state
        runCurrent()
        val secondSession = entrySession<ResumedViewModel>(secondHost) {
            error("the ViewModel already exists")
        }
        val second = secondSession.receive()
        runCurrent()

        assertThat(second.binding).isSameInstanceAs(first.binding)
        assertThat(upstream.activeCollectors).isEqualTo(1)

        firstHost.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(0)

        secondHost.moveTo(Lifecycle.State.RESUMED)
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(1)

        secondHost.moveTo(Lifecycle.State.DESTROYED)
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(0)
        firstSession.cancel()
        secondSession.cancel()
        store.clear()
    }

    @Test
    fun `two retrievals of one host detach independently`() = runTest(dispatcher) {
        val host = LifecycleHost(Lifecycle.State.RESUMED)
        val upstream = TrackedFlow()
        val firstSession = entrySession(host) { ResumedViewModel(upstream) }
        val first = firstSession.receive()
        first.binding.state
        runCurrent()
        val secondSession = entrySession<ResumedViewModel>(host) {
            error("the ViewModel already exists")
        }
        secondSession.receive()
        runCurrent()

        firstSession.cancel()
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(1)

        secondSession.cancel()
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(0)
        host.viewModelStore.clear()
    }

    @Test
    fun `configuration replacement keeps one presenter composition`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val firstHost = LifecycleHost(Lifecycle.State.RESUMED, viewModelStore = store)
        val lifecycleFlow = TrackedFlow()
        val plainFlow = TrackedFlow()
        lateinit var viewModel: LifecycleViewModel
        val firstSession = entrySession(firstHost) {
            LifecycleViewModel(lifecycleFlow, plainFlow).also { viewModel = it }
        }
        val first = firstSession.receive()
        first.binding.state
        runCurrent()
        first.binding.onEvent(Increment)
        advanceUntilIdle()
        assertThat(first.binding.state.value.remembered).isEqualTo(1)
        val saved = firstHost.save()

        firstHost.moveTo(Lifecycle.State.DESTROYED)
        firstSession.cancel()
        runCurrent()

        val secondHost = LifecycleHost(
            initialState = Lifecycle.State.RESUMED,
            restoredState = saved,
            viewModelStore = store,
        )
        val secondSession = entrySession<LifecycleViewModel>(secondHost) {
            error("the ViewModel already exists")
        }
        val second = secondSession.receive()
        runCurrent()

        assertThat(second.viewModel).isSameInstanceAs(viewModel)
        assertThat(second.binding.state.value.remembered).isEqualTo(1)
        assertThat(second.binding.state.value.saveable).isEqualTo(1)
        assertThat(second.binding.state.value.launches).isEqualTo(1)
        assertThat(viewModel.presenterStarts).isEqualTo(1)
        secondSession.cancel()
        store.clear()
    }

    @Test
    fun `an abandoned retrieval does not attach its lifecycle`() = runTest(dispatcher) {
        val host = LifecycleHost(Lifecycle.State.RESUMED)
        val upstream = TrackedFlow()
        lateinit var viewModel: ResumedViewModel
        val factory = viewModelFactory {
            initializer {
                ResumedViewModel(upstream).also { viewModel = it }
            }
        }

        val outcome = runCatching {
            moleculeFlow(RecompositionMode.Immediate) {
                withCompositionLocals(LocalLifecycleOwner provides host) {
                    mauzunViewModel<ResumedViewModel>(
                        viewModelStoreOwner = host,
                        factory = factory,
                    )
                    error("abandon composition")
                }
            }.first()
        }

        assertThat(generateSequence(outcome.exceptionOrNull()) { it.cause }.last().message)
            .isEqualTo("abandon composition")

        viewModel.bindingInstance.state
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(0)
        host.viewModelStore.clear()
    }
}
