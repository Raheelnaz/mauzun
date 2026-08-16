package io.github.raheelnaz.mauzun

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withCompositionLocals
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class WatchedFlow {
    private val values = MutableSharedFlow<Int>(replay = 1).apply { tryEmit(0) }

    var activeCollectors: Int = 0
        private set

    var subscriptions: Int = 0
        private set

    val flow: Flow<Int> = flow {
        activeCollectors++
        subscriptions++
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

private class Owner(initialState: Lifecycle.State) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply {
        currentState = initialState
    }

    override val lifecycle: Lifecycle get() = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

private class WhileActiveViewModel(
    val upstream: WatchedFlow,
    val minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
) : MauzunViewModel<Nothing, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Nothing>): Int {
        val value by upstream.flow.collectAsStateWhileActive(
            initialValue = -1,
            minActiveState = minActiveState,
        )
        return value
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectAsStateWhileActiveTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `collection pauses below the minimum state and resumes with the current value`() =
        runTest(dispatcher) {
            val upstream = WatchedFlow()
            val owner = Owner(Lifecycle.State.STARTED)
            val viewModel = WhileActiveViewModel(upstream)
            viewModel.attachLifecycle(owner.lifecycle)

            val state = viewModel.bindingInstance.state
            advanceUntilIdle()
            assertThat(upstream.activeCollectors).isEqualTo(1)
            upstream.emit(1)
            advanceUntilIdle()
            assertThat(state.value).isEqualTo(1)

            owner.moveTo(Lifecycle.State.CREATED)
            advanceUntilIdle()
            assertThat(upstream.activeCollectors).isEqualTo(0)
            upstream.emit(2)
            advanceUntilIdle()
            assertThat(state.value).isEqualTo(1)

            owner.moveTo(Lifecycle.State.STARTED)
            advanceUntilIdle()
            assertThat(upstream.activeCollectors).isEqualTo(1)
            assertThat(state.value).isEqualTo(2)
        }

    @Test
    fun `moving between active states does not resubscribe`() = runTest(dispatcher) {
        val upstream = WatchedFlow()
        val owner = Owner(Lifecycle.State.RESUMED)
        val viewModel = WhileActiveViewModel(upstream)
        viewModel.attachLifecycle(owner.lifecycle)

        val state = viewModel.bindingInstance.state
        advanceUntilIdle()
        upstream.emit(1)
        advanceUntilIdle()
        assertThat(state.value).isEqualTo(1)

        owner.moveTo(Lifecycle.State.STARTED)
        advanceUntilIdle()
        owner.moveTo(Lifecycle.State.RESUMED)
        advanceUntilIdle()

        assertThat(upstream.subscriptions).isEqualTo(1)
        assertThat(state.value).isEqualTo(1)
    }

    @Test
    fun `a RESUMED floor stays paused at STARTED`() = runTest(dispatcher) {
        val upstream = WatchedFlow()
        val owner = Owner(Lifecycle.State.STARTED)
        val viewModel = WhileActiveViewModel(upstream, minActiveState = Lifecycle.State.RESUMED)
        viewModel.attachLifecycle(owner.lifecycle)

        viewModel.bindingInstance.state
        advanceUntilIdle()
        assertThat(upstream.activeCollectors).isEqualTo(0)

        owner.moveTo(Lifecycle.State.RESUMED)
        advanceUntilIdle()
        assertThat(upstream.activeCollectors).isEqualTo(1)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CollectAsStateWhileActiveNoMainTest {
    @Test
    fun `pauses and resumes without a main dispatcher`() = runTest {
        val upstream = WatchedFlow()
        val owner = Owner(Lifecycle.State.CREATED)
        val states = mutableListOf<Int>()
        backgroundScope.launch {
            moleculeFlow(RecompositionMode.Immediate) {
                withCompositionLocals(LocalLifecycleOwner provides owner) {
                    val value by upstream.flow.collectAsStateWhileActive(initialValue = -1)
                    value
                }
            }.collect { states += it }
        }
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(0)
        assertThat(states.last()).isEqualTo(-1)

        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(1)
        upstream.emit(1)
        runCurrent()
        assertThat(states.last()).isEqualTo(1)

        owner.moveTo(Lifecycle.State.CREATED)
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(0)
        upstream.emit(2)
        runCurrent()
        assertThat(states.last()).isEqualTo(1)

        owner.moveTo(Lifecycle.State.STARTED)
        runCurrent()
        assertThat(upstream.activeCollectors).isEqualTo(1)
        assertThat(states.last()).isEqualTo(2)
    }

    @Test
    fun `INITIALIZED and DESTROYED floors are rejected`() = runTest {
        for (floor in listOf(Lifecycle.State.INITIALIZED, Lifecycle.State.DESTROYED)) {
            val owner = Owner(Lifecycle.State.RESUMED)
            assertFailure {
                moleculeFlow(RecompositionMode.Immediate) {
                    withCompositionLocals(LocalLifecycleOwner provides owner) {
                        val value by MutableStateFlow(0)
                            .collectAsStateWhileActive(minActiveState = floor)
                        value
                    }
                }.first()
            }.isInstanceOf(IllegalArgumentException::class)
        }
    }
}
