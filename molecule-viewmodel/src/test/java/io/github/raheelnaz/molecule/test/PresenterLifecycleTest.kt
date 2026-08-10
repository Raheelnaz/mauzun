package io.github.raheelnaz.molecule.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.raheelnaz.molecule.MoleculeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

private class LifecycleEchoViewModel : MoleculeViewModel<Int, Lifecycle.State, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Lifecycle.State {
        val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
        return state
    }
}

private class PausingViewModel(
    private val source: MutableStateFlow<Int>,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        val n by source.collectAsStateWithLifecycle()
        return n
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PresenterLifecycleTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the presenter composition sees the provided lifecycle`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val vm = LifecycleEchoViewModel()
        store.put("vm", vm)

        val state = vm.testBinding.state
        assertThat(state.value).isEqualTo(Lifecycle.State.RESUMED)

        vm.movePresenterToState(Lifecycle.State.CREATED)
        advanceUntilIdle()
        assertThat(state.value).isEqualTo(Lifecycle.State.CREATED)

        store.clear()
    }

    @Test
    fun `lifecycle aware collection pauses and resumes on the production path`() =
        runTest(dispatcher) {
            val store = ViewModelStore()
            val source = MutableStateFlow(0)
            val vm = PausingViewModel(source)
            store.put("vm", vm)

            val state = vm.testBinding.state
            advanceUntilIdle()
            source.value = 1
            advanceUntilIdle()
            assertThat(state.value).isEqualTo(1)

            vm.movePresenterToState(Lifecycle.State.CREATED)
            advanceUntilIdle()
            source.value = 2
            advanceUntilIdle()
            assertThat(state.value).isEqualTo(1)

            vm.movePresenterToState(Lifecycle.State.RESUMED)
            advanceUntilIdle()
            assertThat(state.value).isEqualTo(2)

            store.clear()
        }
}
