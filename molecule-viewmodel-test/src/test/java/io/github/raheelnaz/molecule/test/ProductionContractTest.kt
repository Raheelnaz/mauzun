package io.github.raheelnaz.molecule.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.hasMessage
import assertk.assertions.isInstanceOf
import io.github.raheelnaz.molecule.MoleculeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

private class EchoProdViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var n by remember { mutableIntStateOf(0) }
        CollectEvents(events) { n = it }
        return n
    }
}

private class TwoCollectorProdViewModel(
    private val seen: MutableList<String>,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        CollectEvents(events) { seen += "first:$it" }
        CollectEvents(events) { seen += "second:$it" }
        return 0
    }
}

private class WatchingProdViewModel(
    private val log: MutableList<String>,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var query by remember { mutableIntStateOf(0) }
        CollectEvents(events) { query = it }

        LaunchedEffect(Unit) {
            snapshotFlow { query }.collect { log += "saw:$it" }
        }
        return query
    }
}

private class EffectProdViewModel : MoleculeViewModel<Int, Int, String>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        CollectEvents(events) { emitEffect("e$it") }
        return 0
    }
}

private sealed interface ProdEvent
private data class Inc(val by: Int) : ProdEvent
private data object Skip : ProdEvent

private class TypedProdViewModel : MoleculeViewModel<ProdEvent, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<ProdEvent>): Int {
        var n by remember { mutableIntStateOf(0) }
        CollectEventsOf<Inc>(events) { n += it.by }
        return n
    }
}

private class EffectFloodViewModel : MoleculeViewModel<Int, Int, String>() {
    @Composable
    override fun present(events: Flow<Int>): Int = 0

    fun flood(effect: String) = emitEffect(effect)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProductionContractTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private var stored = 0

    private fun <T : MoleculeViewModel<*, *, *>> T.tracked(): T {
        store.put("vm${stored++}", this)
        return this
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `events sent before state starts are delivered`() = runTest(dispatcher) {
        val vm = EchoProdViewModel().tracked()
        vm.onEvent(41)
        vm.onEvent(42)

        val state = vm.state
        advanceUntilIdle()

        assertThat(state.value).isEqualTo(42)
    }

    @Test
    fun `events broadcast to every collector on the production path`() = runTest(dispatcher) {
        val seen = mutableListOf<String>()
        val vm = TwoCollectorProdViewModel(seen).tracked()

        vm.state
        advanceUntilIdle()
        vm.onEvent(7)
        advanceUntilIdle()

        assertThat(seen).containsExactlyInAnyOrder("first:7", "second:7")
    }

    @Test
    fun `effects wait for a collector and keep their order`() = runTest(dispatcher) {
        val vm = EffectProdViewModel().tracked()
        vm.state
        advanceUntilIdle()

        vm.onEvent(1)
        vm.onEvent(2)
        advanceUntilIdle()

        vm.effects.test {
            assertThat(awaitItem()).isEqualTo("e1")
            assertThat(awaitItem()).isEqualTo("e2")
        }
    }

    @Test
    fun `effects buffered when a collector dies reach the next collector`() = runTest(dispatcher) {
        val vm = EffectFloodViewModel().tracked()
        vm.flood("first")
        vm.flood("second")

        val delivered = mutableListOf<String>()
        val slowCollector = launch {
            vm.effects.collect {
                delivered += it
                delay(1_000)
            }
        }
        slowCollector.cancel()
        slowCollector.join()

        assertThat(delivered).containsExactly("first")

        vm.effects.test {
            assertThat(awaitItem()).isEqualTo("second")
        }
    }

    @Test
    fun `snapshotFlow observes presenter state on the production path`() = runTest(dispatcher) {
        val log = mutableListOf<String>()
        val vm = WatchingProdViewModel(log).tracked()

        vm.state
        advanceUntilIdle()
        vm.onEvent(1)
        advanceUntilIdle()

        assertThat(log).containsExactly("saw:0", "saw:1")
    }

    @Test
    fun `the event buffer throws on the 51st unconsumed event`() {
        val vm = EchoProdViewModel()
        repeat(50) { vm.onEvent(it) }
        assertFailure { vm.onEvent(50) }.isInstanceOf(IllegalStateException::class)
    }

    @Test
    fun `events after onCleared are dropped instead of crashing`() {
        val store = ViewModelStore()
        val vm = EchoProdViewModel()
        store.put("vm", vm)
        store.clear()
        repeat(100) { vm.onEvent(it) }
    }

    @Test
    fun `CollectEventsOf filters the real event channel`() = runTest(dispatcher) {
        val vm = TypedProdViewModel().tracked()
        val state = vm.state
        advanceUntilIdle()

        vm.onEvent(Skip)
        vm.onEvent(Inc(2))
        vm.onEvent(Skip)
        vm.onEvent(Inc(3))
        advanceUntilIdle()

        assertThat(state.value).isEqualTo(5)
    }

    @Test
    fun `the effects flow completes when the ViewModel is cleared`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val vm = EffectProdViewModel()
        store.put("vm", vm)
        vm.state
        advanceUntilIdle()
        vm.onEvent(1)
        advanceUntilIdle()

        store.clear()

        vm.effects.test {
            assertThat(awaitItem()).isEqualTo("e1")
            awaitComplete()
        }
    }

    @Test
    fun `an effect caught mid-handoff by cancellation returns to the buffer`() {
        // Deferred dispatch like production Main: the send resumes the suspended collector, and
        // the cancellation wins the race before that resumption runs.
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            runTest(main) {
                val store = ViewModelStore()
                val vm = EffectFloodViewModel()
                store.put("vm", vm)
                try {
                    val delivered = mutableListOf<String>()
                    val racer = launch { vm.effects.collect { delivered += it } }
                    runCurrent()
                    vm.flood("racy")
                    racer.cancel()
                    runCurrent()

                    assertThat(delivered).isEmpty()

                    vm.effects.test {
                        assertThat(awaitItem()).isEqualTo("racy")
                    }
                } finally {
                    store.clear()
                    advanceUntilIdle()
                }
            }
        } finally {
            Dispatchers.setMain(dispatcher)
        }
    }

    @Test
    fun `redelivery loses the effect when the buffer refilled behind it`() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            runTest(main) {
                val store = ViewModelStore()
                val vm = EffectFloodViewModel()
                store.put("vm", vm)
                try {
                    val delivered = mutableListOf<String>()
                    val racer = launch { vm.effects.collect { delivered += it } }
                    runCurrent()
                    vm.flood("racy")
                    repeat(50) { vm.flood("filler$it") }
                    racer.cancel()
                    runCurrent()

                    assertThat(delivered).isEmpty()

                    vm.effects.test {
                        repeat(50) { assertThat(awaitItem()).isEqualTo("filler$it") }
                    }
                } finally {
                    store.clear()
                    advanceUntilIdle()
                }
            }
        } finally {
            Dispatchers.setMain(dispatcher)
        }
    }

    @Test
    fun `a redelivered effect lands behind one buffered meanwhile`() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            runTest(main) {
                val store = ViewModelStore()
                val vm = EffectFloodViewModel()
                store.put("vm", vm)
                try {
                    val racer = launch { vm.effects.collect { } }
                    runCurrent()
                    vm.flood("first")
                    vm.flood("second")
                    racer.cancel()
                    runCurrent()

                    vm.effects.test {
                        assertThat(awaitItem()).isEqualTo("second")
                        assertThat(awaitItem()).isEqualTo("first")
                    }
                } finally {
                    store.clear()
                    advanceUntilIdle()
                }
            }
        } finally {
            Dispatchers.setMain(dispatcher)
        }
    }

    @Test
    fun `effects after onCleared are dropped instead of crashing`() {
        val store = ViewModelStore()
        val vm = EffectFloodViewModel()
        store.put("vm", vm)
        store.clear()
        repeat(100) { vm.flood("late$it") }
    }

    @Test
    fun `the effect buffer throws on effect 51 when nothing collects`() {
        val vm = EffectFloodViewModel()
        repeat(50) { vm.flood("e$it") }
        assertFailure { vm.flood("boom") }.isInstanceOf(IllegalStateException::class)
    }

    @Test
    fun `events sent before startup reach every collector`() {
        // Deferred dispatch like production Main: both collectors subscribe during the initial
        // composition before the event pump runs.
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            runTest(main) {
                val seen = mutableListOf<String>()
                val store = ViewModelStore()
                val vm = TwoCollectorProdViewModel(seen)
                store.put("vm", vm)
                vm.onEvent(9)
                try {
                    vm.state
                    advanceUntilIdle()
                    assertThat(seen).containsExactlyInAnyOrder("first:9", "second:9")
                } finally {
                    store.clear()
                    advanceUntilIdle()
                }
            }
        } finally {
            Dispatchers.setMain(dispatcher)
        }
    }

    @Test
    fun `state has a value the moment the getter returns`() {
        val vm = EchoProdViewModel().tracked()
        assertThat(vm.state.value).isEqualTo(0)
    }

    @Test
    fun `clearing immediately after starting state does not crash`() = runTest(dispatcher) {
        repeat(20) {
            val store = ViewModelStore()
            val vm = EchoProdViewModel()
            store.put("vm", vm)
            vm.state
            store.clear()
        }
        advanceUntilIdle()
    }

    @Test
    fun `reading state for the first time after clear names the mistake`() {
        val store = ViewModelStore()
        val vm = EchoProdViewModel()
        store.put("vm", vm)
        store.clear()
        assertFailure { vm.state }
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("state was first read after the ViewModel was cleared")
    }
}
