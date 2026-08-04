package io.github.raheelnaz.molecule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

private class OpaqueBox

private class OpaqueViewModel : MoleculeViewModel<Int, Int, Nothing> {
    constructor() : super()
    constructor(handle: SavedStateHandle) : super(handle)

    @Composable
    override fun present(events: Flow<Int>): Int {
        val box = rememberSaveable { mutableStateOf(OpaqueBox()) }
        check(box.value === box.value)
        return 0
    }
}

private class LambdaViewModel(handle: SavedStateHandle) :
    MoleculeViewModel<Int, Int, Nothing>(handle) {
    @Composable
    override fun present(events: Flow<Int>): Int {
        val block = rememberSaveable<() -> Int> { { 1 } }
        return block()
    }
}

private class CounterViewModel(handle: SavedStateHandle) :
    MoleculeViewModel<Int, Int, Nothing>(handle) {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var value by rememberSaveable { mutableIntStateOf(0) }
        CollectEvents(events) { value = it }
        return value
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SavedStateTest {

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
    fun `rememberSaveable without a handle behaves like remember`() = runTest(dispatcher) {
        val vm = OpaqueViewModel().tracked()

        assertThat(vm.state.value).isEqualTo(0)
    }

    @Test
    fun `a value the registry cannot store fails at registration`() = runTest(dispatcher) {
        val vm = OpaqueViewModel(SavedStateHandle()).tracked()

        assertFailure { vm.state }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `a serializable lambda is rejected`() = runTest(dispatcher) {
        val vm = LambdaViewModel(SavedStateHandle()).tracked()

        assertFailure { vm.state }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `a malformed envelope is discarded instead of crashing`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(PresenterSavedState.KEY to "garbage"))
        val vm = CounterViewModel(handle).tracked()

        assertThat(vm.state.value).isEqualTo(0)
    }
}
