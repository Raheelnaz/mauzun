package io.github.raheelnaz.molecule.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.testing.TestLifecycleOwner
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import io.github.raheelnaz.molecule.PresenterBinding
import io.github.raheelnaz.molecule.PresenterHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

private class FakeBinding : PresenterBinding<Int, Int, String> {
    override val state = MutableStateFlow(0)
    private val effectChannel = Channel<String>(capacity = 50)
    override val effects: Flow<String> = effectChannel.receiveAsFlow()
    val received = mutableListOf<Int>()

    override fun onEvent(event: Int) {
        received += event
    }

    fun emitEffect(effect: String) {
        effectChannel.trySend(effect)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PresenterHostTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.host(
        owner: TestLifecycleOwner,
        body: @Composable () -> Unit,
    ) {
        backgroundScope.launch {
            moleculeFlow(RecompositionMode.Immediate) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) { body() }
            }.collect {}
        }
        runCurrent()
    }

    @Test
    fun `content sees the latest model and forwards events`() = runTest(dispatcher) {
        val binding = FakeBinding()
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, dispatcher)
        val models = mutableListOf<Int>()
        var send: ((Int) -> Unit)? = null

        host(owner) {
            PresenterHost(binding, onEffect = {}) { state, onEvent ->
                models += state
                send = onEvent
            }
        }

        assertThat(models).containsExactly(0)

        binding.state.value = 1
        runCurrent()
        assertThat(models).containsExactly(0, 1)

        send!!(42)
        assertThat(binding.received).containsExactly(42)
    }

    @Test
    fun `models wait while the lifecycle is stopped`() = runTest(dispatcher) {
        val binding = FakeBinding()
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, dispatcher)
        val models = mutableListOf<Int>()

        host(owner) {
            PresenterHost(binding, onEffect = {}) { state, _ -> models += state }
        }

        assertThat(models).containsExactly(0)

        owner.currentState = Lifecycle.State.CREATED
        runCurrent()
        binding.state.value = 1
        runCurrent()
        assertThat(models).containsExactly(0)

        owner.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertThat(models).containsExactly(0, 1)
    }

    @Test
    fun `RESUMED as the minimum holds effects until resumed`() = runTest(dispatcher) {
        val binding = FakeBinding()
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, dispatcher)
        val handled = mutableListOf<String>()

        host(owner) {
            PresenterHost(
                binding,
                onEffect = { handled += it },
                effectsMinActiveState = Lifecycle.State.RESUMED,
            ) { _, _ -> }
        }

        binding.emitEffect("early")
        runCurrent()
        assertThat(handled).isEmpty()

        owner.currentState = Lifecycle.State.RESUMED
        runCurrent()
        assertThat(handled).containsExactly("early")
    }

    @Test
    fun `effects are not handled below STARTED`() = runTest(dispatcher) {
        val binding = FakeBinding()
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED, dispatcher)
        val handled = mutableListOf<String>()

        host(owner) {
            PresenterHost(binding, onEffect = { handled += it }) { _, _ -> }
        }

        binding.emitEffect("early")
        runCurrent()

        assertThat(handled).isEmpty()
    }

    @Test
    fun `entering STARTED delivers the buffered effect`() = runTest(dispatcher) {
        val binding = FakeBinding()
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED, dispatcher)
        val handled = mutableListOf<String>()

        host(owner) {
            PresenterHost(binding, onEffect = { handled += it }) { _, _ -> }
        }

        binding.emitEffect("early")
        runCurrent()

        owner.currentState = Lifecycle.State.STARTED
        runCurrent()

        assertThat(handled).containsExactly("early")
    }

    @Test
    fun `effects pause while stopped and resume afterward`() = runTest(dispatcher) {
        val binding = FakeBinding()
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, dispatcher)
        val handled = mutableListOf<String>()

        host(owner) {
            PresenterHost(binding, onEffect = { handled += it }) { _, _ -> }
        }

        binding.emitEffect("first")
        runCurrent()
        assertThat(handled).containsExactly("first")

        owner.currentState = Lifecycle.State.CREATED
        runCurrent()
        binding.emitEffect("second")
        runCurrent()
        assertThat(handled).containsExactly("first")

        owner.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertThat(handled).containsExactly("first", "second")
    }

    @Test
    fun `recomposition swaps in the latest onEffect`() = runTest(dispatcher) {
        val binding = FakeBinding()
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, dispatcher)
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        var handler by mutableStateOf<suspend (String) -> Unit>({ first += it })

        host(owner) {
            PresenterHost(binding, onEffect = handler) { _, _ -> }
        }

        binding.emitEffect("one")
        runCurrent()

        handler = { second += it }
        runCurrent()
        binding.emitEffect("two")
        runCurrent()

        assertThat(first).containsExactly("one")
        assertThat(second).containsExactly("two")
    }

    @Test
    fun `INITIALIZED and DESTROYED are rejected as minimum effect states`() = runTest(dispatcher) {
        val binding = FakeBinding()
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED, dispatcher)

        for (state in listOf(Lifecycle.State.INITIALIZED, Lifecycle.State.DESTROYED)) {
            val outcome = runCatching {
                moleculeFlow(RecompositionMode.Immediate) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        PresenterHost(
                            binding,
                            onEffect = {},
                            effectsMinActiveState = state,
                        ) { _, _ -> }
                    }
                }.first()
            }

            val root = generateSequence(outcome.exceptionOrNull()) { it.cause }.lastOrNull()
            assertThat(root)
                .isNotNull()
                .isInstanceOf(IllegalArgumentException::class)
                .hasMessage("effectsMinActiveState must be CREATED, STARTED, or RESUMED")
        }
    }
}
