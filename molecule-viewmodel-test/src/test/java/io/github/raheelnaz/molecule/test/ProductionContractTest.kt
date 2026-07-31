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
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.github.raheelnaz.molecule.MoleculeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProductionContractTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `events sent before state starts are delivered`() = runTest(dispatcher) {
        val vm = EchoProdViewModel()
        vm.onEvent(41)
        vm.onEvent(42)

        val state = vm.state
        advanceUntilIdle()

        assertThat(state.value).isEqualTo(42)
    }

    @Test
    fun `events broadcast to every collector on the production path`() = runTest(dispatcher) {
        val seen = mutableListOf<String>()
        val vm = TwoCollectorProdViewModel(seen)

        vm.state
        advanceUntilIdle()
        vm.onEvent(7)
        advanceUntilIdle()

        assertThat(seen).containsExactlyInAnyOrder("first:7", "second:7")
    }

    @Test
    fun `effects wait for a collector and keep their order`() = runTest(dispatcher) {
        val vm = EffectProdViewModel()
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
    fun `snapshotFlow observes presenter state on the production path`() = runTest(dispatcher) {
        val log = mutableListOf<String>()
        val vm = WatchingProdViewModel(log)

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
}
