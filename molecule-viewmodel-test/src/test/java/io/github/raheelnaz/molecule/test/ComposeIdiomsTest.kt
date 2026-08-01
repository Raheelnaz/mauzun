package io.github.raheelnaz.molecule.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModelStore
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import io.github.raheelnaz.molecule.MoleculeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

private class DerivingViewModel(
    private val compositions: MutableList<String>,
) : MoleculeViewModel<Int, Boolean, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Boolean {
        var count by remember { mutableIntStateOf(0) }
        CollectEvents(events) { count = it }
        val ready by remember { derivedStateOf { count >= 2 } }
        SideEffect { compositions += "composed" }
        return ready
    }
}

private sealed interface LabelEvent
private data class SetLabel(val value: String) : LabelEvent
private data object Snap : LabelEvent

private class UpdatedStateViewModel(
    private val log: MutableList<String>,
) : MoleculeViewModel<LabelEvent, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<LabelEvent>): Int {
        var label by remember { mutableStateOf("a") }
        val current by rememberUpdatedState(label)
        CollectEvents(events) { if (it is SetLabel) label = it.value }
        LaunchedEffect(Unit) {
            log += "started"
            events.collect { if (it is Snap) log += "snap:$current" }
        }
        return 0
    }
}

private class SideEffectViewModel(
    private val log: MutableList<String>,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var n by remember { mutableIntStateOf(0) }
        CollectEvents(events) { n = it }
        SideEffect { log += "composed:$n" }
        return n
    }
}

private class CollectAsStateViewModel(
    private val source: MutableStateFlow<Int>,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        val n by source.collectAsState(initial = -1)
        return n
    }
}

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

    @Test
    fun `derivedStateOf skips recomposition when the result is unchanged`() = runTest {
        val compositions = mutableListOf<String>()
        DerivingViewModel(compositions).test {
            assertThat(awaitState()).isEqualTo(false)
            assertThat(compositions).hasSize(1)
            sendEvent(1)
            expectNoStateChanges()
            assertThat(compositions).hasSize(1)
            sendEvent(2)
            assertThat(awaitState()).isEqualTo(true)
            assertThat(compositions).hasSize(2)
        }
    }

    @Test
    fun `rememberUpdatedState exposes the latest value without restarting the effect`() = runTest {
        val log = mutableListOf<String>()
        UpdatedStateViewModel(log).test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(SetLabel("b"))
            sendEvent(Snap)
            assertThat(log).containsExactly("started", "snap:b")
        }
    }

    @Test
    fun `SideEffect runs after every composition`() = runTest {
        val log = mutableListOf<String>()
        SideEffectViewModel(log).test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(3)
            assertThat(awaitState()).isEqualTo(3)
            assertThat(log).containsExactly("composed:0", "composed:3")
        }
    }

    @Test
    fun `collectAsState with a different initial value keeps recomposing`() = runTest {
        val source = MutableStateFlow(0)
        CollectAsStateViewModel(source).test {
            assertThat(awaitState()).isEqualTo(-1)
            assertThat(awaitState()).isEqualTo(0)
            source.value = 7
            assertThat(awaitState()).isEqualTo(7)
        }
    }
}
