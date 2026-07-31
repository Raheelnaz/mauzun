package io.github.raheelnaz.molecule.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModelStore
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import io.github.raheelnaz.molecule.MoleculeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

private class DisposingViewModel(
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

private class ProducingViewModel(
    val source: Channel<Int>,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        val n by produceState(0) {
            for (v in source) value = v
        }
        return n
    }
}

private class WatchingViewModel(
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

/** Ordinary Compose effect APIs work inside presenters. These pin the ones beyond LaunchedEffect. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeIdiomsTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `DisposableEffect disposes when the ViewModel is cleared`() = runTest(dispatcher) {
        val log = mutableListOf<String>()
        val store = ViewModelStore()
        val vm = DisposingViewModel(log)
        store.put("vm", vm)

        vm.state
        advanceUntilIdle()
        assertThat(log).containsExactly("effect")

        store.clear()
        advanceUntilIdle()
        assertThat(log).containsExactly("effect", "dispose")
    }

    @Test
    fun `snapshotFlow observes presenter state`() = runTest {
        val log = mutableListOf<String>()
        WatchingViewModel(log).test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(1)
            assertThat(awaitState()).isEqualTo(1)
            assertThat(log).containsExactly("saw:0", "saw:1")
        }
    }

    @Test
    fun `produceState drives the model`() = runTest {
        val source = Channel<Int>()
        ProducingViewModel(source).test {
            assertThat(awaitState()).isEqualTo(0)
            source.send(5)
            assertThat(awaitState()).isEqualTo(5)
            source.send(9)
            assertThat(awaitState()).isEqualTo(9)
        }
    }
}
