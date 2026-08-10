package io.github.raheelnaz.molecule.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
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
import assertk.assertions.isNotNull
import io.github.raheelnaz.molecule.MoleculeViewModel
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
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

// Standard test dispatchers do not model Android's Main/Main.immediate distinction.
private class LooperMainDispatcher : MainCoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override val immediate: MainCoroutineDispatcher = object : MainCoroutineDispatcher() {
        override val immediate: MainCoroutineDispatcher get() = this
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue += block
    }

    fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
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

private class DisposingProdViewModel(
    private val log: MutableList<String>,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        DisposableEffect(Unit) {
            log += "effect"
            onDispose { log += "dispose" }
        }
        return 0
    }
}

private class RecursiveReadViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        // The misuse under test: consumers cannot write it since 0.8, the guard stays for
        // in-module regressions, and lint still flags the read.
        @Suppress("StateFlowValueCalledInComposition")
        testBinding.state.value
        return 0
    }
}

private class ThrowOnFirstCompositionViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    var compositions = 0

    @Composable
    override fun present(events: Flow<Int>): Int {
        compositions++
        error("boom in present")
    }
}

private class CancellingHandlerProdViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var n by remember { mutableIntStateOf(0) }
        CollectEvents(events) { if (it == 2) throw CancellationException("spurious") }
        CollectEvents(events) { n = it }
        return n
    }
}

private class CrashOnEventProdViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        CollectEvents(events) { error("boom in handler") }
        return 0
    }
}

private class ThreadRecordingViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    val threads = mutableListOf<String>()

    @Composable
    override fun present(events: Flow<Int>): Int {
        threads += Thread.currentThread().name
        return 0
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

        val state = vm.testBinding.state
        advanceUntilIdle()

        assertThat(state.value).isEqualTo(42)
    }

    @Test
    fun `events broadcast to every collector on the production path`() = runTest(dispatcher) {
        val seen = mutableListOf<String>()
        val vm = TwoCollectorProdViewModel(seen).tracked()

        vm.testBinding.state
        advanceUntilIdle()
        vm.onEvent(7)
        advanceUntilIdle()

        assertThat(seen).containsExactlyInAnyOrder("first:7", "second:7")
    }

    @Test
    fun `effects wait for a collector and keep their order`() = runTest(dispatcher) {
        val vm = EffectProdViewModel().tracked()
        vm.testBinding.state
        advanceUntilIdle()

        vm.onEvent(1)
        vm.onEvent(2)
        advanceUntilIdle()

        vm.testBinding.effects.test {
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
            vm.testBinding.effects.collect {
                delivered += it
                delay(1_000)
            }
        }
        slowCollector.cancel()
        slowCollector.join()

        assertThat(delivered).containsExactly("first")

        vm.testBinding.effects.test {
            assertThat(awaitItem()).isEqualTo("second")
        }
    }

    @Test
    fun `snapshotFlow observes presenter state on the production path`() = runTest(dispatcher) {
        val log = mutableListOf<String>()
        val vm = WatchingProdViewModel(log).tracked()

        vm.testBinding.state
        advanceUntilIdle()
        vm.onEvent(1)
        advanceUntilIdle()

        assertThat(log).containsExactly("saw:0", "saw:1")
    }

    @Test
    fun `the event buffer throws on the 51st unconsumed event`() {
        val vm = EchoProdViewModel()
        repeat(50) { vm.onEvent(it) }
        assertFailure { vm.onEvent(50) }
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("Event buffer overflow in EchoProdViewModel (latest: Int)")
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
        val state = vm.testBinding.state
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
        vm.testBinding.state
        advanceUntilIdle()
        vm.onEvent(1)
        advanceUntilIdle()

        store.clear()

        vm.testBinding.effects.test {
            assertThat(awaitItem()).isEqualTo("e1")
            awaitComplete()
        }
    }

    @Test
    fun `an effect caught mid-handoff by cancellation returns to the buffer`() {
        // Delay collector resumption so cancellation wins the handoff race.
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            runTest(main) {
                val store = ViewModelStore()
                val vm = EffectFloodViewModel()
                store.put("vm", vm)
                try {
                    val delivered = mutableListOf<String>()
                    val racer = launch { vm.testBinding.effects.collect { delivered += it } }
                    runCurrent()
                    vm.flood("racy")
                    racer.cancel()
                    runCurrent()

                    assertThat(delivered).isEmpty()

                    vm.testBinding.effects.test {
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
                    val racer = launch { vm.testBinding.effects.collect { delivered += it } }
                    runCurrent()
                    vm.flood("racy")
                    repeat(50) { vm.flood("filler$it") }
                    racer.cancel()
                    runCurrent()

                    assertThat(delivered).isEmpty()

                    vm.testBinding.effects.test {
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
                    val racer = launch { vm.testBinding.effects.collect { } }
                    runCurrent()
                    vm.flood("first")
                    vm.flood("second")
                    racer.cancel()
                    runCurrent()

                    vm.testBinding.effects.test {
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
        assertFailure { vm.flood("boom") }
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("Effect buffer overflow in EffectFloodViewModel (latest: String)")
    }

    @Test
    fun `events sent before startup reach every collector`() {
        // Defer Main so both collectors subscribe before the event relay runs.
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
                    vm.testBinding.state
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
    fun `an early event reaches every collector when Main defers and Main-immediate does not`() {
        val looper = LooperMainDispatcher()
        Dispatchers.setMain(looper)
        try {
            val seen = mutableListOf<String>()
            val store = ViewModelStore()
            val vm = TwoCollectorProdViewModel(seen)
            store.put("vm", vm)
            try {
                vm.onEvent(9)
                vm.testBinding.state
                looper.drain()

                assertThat(seen).containsExactlyInAnyOrder("first:9", "second:9")
            } finally {
                store.clear()
                looper.drain()
            }
        } finally {
            Dispatchers.setMain(dispatcher)
        }
    }

    @Test
    fun `state has a value the moment the getter returns`() {
        val vm = EchoProdViewModel().tracked()
        assertThat(vm.testBinding.state.value).isEqualTo(0)
    }

    @Test
    fun `clearing immediately after starting state does not crash`() = runTest(dispatcher) {
        repeat(20) {
            val store = ViewModelStore()
            val vm = EchoProdViewModel()
            store.put("vm", vm)
            vm.testBinding.state
            store.clear()
        }
        advanceUntilIdle()
    }

    @Test
    fun `a presenter that throws during its first composition composes once`() {
        val vm = ThrowOnFirstCompositionViewModel().tracked()

        assertFailure { vm.testBinding.state }
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("boom in present")

        val secondRead = runCatching { vm.testBinding.state }.exceptionOrNull()
        assertThat(secondRead)
            .isNotNull()
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("the presenter already failed to start")
        assertThat(secondRead?.cause).isNotNull().hasMessage("boom in present")

        assertThat(vm.compositions).isEqualTo(1)
    }

    @Test
    fun `a failed start does not leave a recomposer running`() {
        val running = Recomposer.runningRecomposers.value.size
        val vm = ThrowOnFirstCompositionViewModel().tracked()

        assertFailure { vm.testBinding.state }

        assertThat(Recomposer.runningRecomposers.value.size).isEqualTo(running)
    }

    @Test
    fun `a handler cancellation takes only that collector on the production path`() =
        runTest(dispatcher) {
            val vm = CancellingHandlerProdViewModel().tracked()
            val state = vm.testBinding.state
            advanceUntilIdle()

            vm.onEvent(1)
            vm.onEvent(2)
            vm.onEvent(3)
            advanceUntilIdle()

            assertThat(state.value).isEqualTo(3)
        }

    @Test
    fun `a presenter crash stops the event relay instead of draining silently`() {
        val outcome = runCatching {
            runTest(dispatcher) {
                val vm = CrashOnEventProdViewModel().tracked()
                vm.testBinding.state
                advanceUntilIdle()
                vm.onEvent(1)
                advanceUntilIdle()

                repeat(50) { vm.onEvent(it) }
                assertFailure { vm.onEvent(99) }
                    .isInstanceOf(IllegalStateException::class)
                    .hasMessage("Event buffer overflow in CrashOnEventProdViewModel (latest: Int)")
            }
        }

        val root = generateSequence(outcome.exceptionOrNull()) { it.cause }.lastOrNull()
        assertThat(root)
            .isNotNull()
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("boom in handler")
    }

    @Test
    fun `the first composition runs on the thread that first reads state`() {
        // The initial composition runs in the state getter's call frame.
        val vm = ThreadRecordingViewModel().tracked()
        val reader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "reader") }
        try {
            reader.submit { vm.testBinding.state }.get()

            assertThat(vm.threads).containsExactly("reader")
        } finally {
            reader.shutdown()
        }
    }

    @Test
    fun `reading state for the first time after clear names the mistake`() {
        val store = ViewModelStore()
        val vm = EchoProdViewModel()
        store.put("vm", vm)
        store.clear()
        assertFailure { vm.testBinding.state }
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("state was first read after the ViewModel was cleared")
    }

    @Test
    fun `a state read inside present fails instead of composing twice`() {
        val vm = RecursiveReadViewModel().tracked()

        assertFailure { vm.testBinding.state }
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("the presenter is already starting")
    }

    @Test
    fun `DisposableEffect disposes when the ViewModel is cleared`() = runTest(dispatcher) {
        val log = mutableListOf<String>()
        val store = ViewModelStore()
        val vm = DisposingProdViewModel(log)
        store.put("vm", vm)

        vm.testBinding.state
        advanceUntilIdle()
        assertThat(log).containsExactly("effect")

        store.clear()
        advanceUntilIdle()
        assertThat(log).containsExactly("effect", "dispose")
    }
}
