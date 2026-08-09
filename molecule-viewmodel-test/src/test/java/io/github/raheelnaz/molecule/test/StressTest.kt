package io.github.raheelnaz.molecule.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import io.github.raheelnaz.molecule.MoleculeViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
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

private class CountingViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    val seen = AtomicInteger()

    @Composable
    override fun present(events: Flow<Int>): Int {
        CollectEvents(events) { seen.incrementAndGet() }
        return 0
    }
}

private class StormViewModel : MoleculeViewModel<Int, Int, String>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var n by remember { mutableIntStateOf(0) }
        CollectEvents(events) {
            n += 1
            emitEffect("e$it")
        }
        return n
    }
}

private class DirectEffectViewModel : MoleculeViewModel<Int, Int, String>() {
    @Composable
    override fun present(events: Flow<Int>): Int = 0

    fun send(effect: String) = emitEffect(effect)
}

private class DiesOnEventViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        CollectEvents(events) { error("dead") }
        return 0
    }
}

private class DiesOnStartViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int = error("dead on arrival")
}

private class StallingViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        CollectEvents(events) { awaitCancellation() }
        return 0
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StressTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `onEvent is safe to call from many threads at once`() {
        val vm = CountingViewModel()
        store.put("vm", vm)
        vm.state

        // 48 sends per round stay under the 50 slot queue even if nothing drains mid-round.
        val threads = 8
        val rounds = 50
        val perRound = 6
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(rounds) { round ->
                val start = CountDownLatch(1)
                val workers = (0 until threads).map { t ->
                    pool.submit {
                        start.await()
                        repeat(perRound) { i -> vm.onEvent(round + t + i) }
                    }
                }
                start.countDown()
                // get() rethrows a worker's failure instead of hanging the test on a latch.
                workers.forEach { it.get() }
            }
        } finally {
            pool.shutdown()
        }

        assertThat(vm.seen.get()).isEqualTo(threads * rounds * perRound)
    }

    @Test
    fun `a seeded storm keeps the model and the effects exact`() = runTest(dispatcher) {
        val random = Random(20260809)
        val vm = StormViewModel()
        store.put("vm", vm)
        val state = vm.state

        val delivered = mutableListOf<String>()
        var collector: Job? = null
        var sent = 0

        repeat(2_000) {
            when (random.nextInt(4)) {
                0 -> if (sent - delivered.size < 40) {
                    vm.onEvent(sent)
                    sent += 1
                }
                1 -> if (collector == null) {
                    collector = launch { vm.effects.collect { delivered += it } }
                }
                2 -> {
                    collector?.cancel()
                    collector = null
                }
                3 -> runCurrent()
            }
        }
        collector?.cancel()
        val drain = launch { vm.effects.collect { delivered += it } }
        advanceUntilIdle()
        drain.cancel()

        assertThat(state.value).isEqualTo(sent)
        assertThat(delivered.size).isEqualTo(sent)
        assertThat(delivered.toSet().size).isEqualTo(sent)
    }

    @Test
    fun `collector churn delivers every effect exactly once`() {
        // Deferred dispatch opens the mid-handoff window, so cancellation hits redelivery.
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        try {
            runTest(main) {
                val random = Random(31)
                val vm = DirectEffectViewModel()
                val churnStore = ViewModelStore()
                churnStore.put("vm", vm)
                try {
                    val delivered = mutableListOf<String>()
                    var collector: Job? = null
                    var sent = 0

                    repeat(400) {
                        when (random.nextInt(4)) {
                            0 -> if (sent - delivered.size < 40) {
                                vm.send("e$sent")
                                sent += 1
                            }
                            1 -> if (collector == null) {
                                collector = launch { vm.effects.collect { delivered += it } }
                            }
                            2 -> {
                                collector?.cancel()
                                collector = null
                            }
                            3 -> runCurrent()
                        }
                    }
                    collector?.cancel()
                    val drain = launch { vm.effects.collect { delivered += it } }
                    advanceUntilIdle()
                    drain.cancel()

                    assertThat(delivered.size).isEqualTo(sent)
                    assertThat(delivered.toSet().size).isEqualTo(sent)
                } finally {
                    churnStore.clear()
                    advanceUntilIdle()
                }
            }
        } finally {
            Dispatchers.setMain(dispatcher)
        }
    }

    @Test
    fun `a stalled handler jams the pipeline at a known depth`() = runTest(dispatcher) {
        val vm = StallingViewModel()
        store.put("stalled", vm)
        vm.state
        advanceUntilIdle()

        var accepted = 0
        val outcome = runCatching {
            while (true) {
                vm.onEvent(accepted)
                accepted += 1
                advanceUntilIdle()
            }
        }

        // One event in the stuck handler, one in the relay, 64 buffered, 50 queued.
        assertThat(accepted).isEqualTo(116)
        val root = generateSequence(outcome.exceptionOrNull()) { it.cause }.lastOrNull()
        assertThat(root)
            .isNotNull()
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("Event buffer overflow in StallingViewModel (latest: Int)")
    }

    @Test
    fun `every way a presenter dies leaves no recomposer running`() {
        val baseline = Recomposer.runningRecomposers.value.size

        // A start that throws. The count has to be back before clear, or clear hides the leak.
        run {
            val deadStore = ViewModelStore()
            val vm = DiesOnStartViewModel()
            deadStore.put("vm", vm)
            try {
                assertThat(runCatching { vm.state }.isFailure).isEqualTo(true)
                assertThat(Recomposer.runningRecomposers.value.size).isEqualTo(baseline)
            } finally {
                deadStore.clear()
            }
        }

        // A handler that throws. runTest reports the crash here instead of the next test.
        run {
            val deadStore = ViewModelStore()
            val vm = DiesOnEventViewModel()
            deadStore.put("vm", vm)
            try {
                val outcome = runCatching {
                    runTest(dispatcher) {
                        vm.state
                        advanceUntilIdle()
                        vm.onEvent(1)
                        advanceUntilIdle()
                    }
                }
                val root = generateSequence(outcome.exceptionOrNull()) { it.cause }.lastOrNull()
                assertThat(root).isNotNull().hasMessage("dead")
                assertThat(Recomposer.runningRecomposers.value.size).isEqualTo(baseline)
            } finally {
                deadStore.clear()
            }
        }

        // A plain clear.
        run {
            val deadStore = ViewModelStore()
            val vm = CountingViewModel()
            deadStore.put("vm", vm)
            vm.state
            deadStore.clear()
        }
        assertThat(Recomposer.runningRecomposers.value.size).isEqualTo(baseline)
    }
}
