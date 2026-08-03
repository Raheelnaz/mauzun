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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

private class SaveableViewModel(handle: SavedStateHandle?) :
    MoleculeViewModel<Int, Int, Nothing>(handle) {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var value by rememberSaveable { mutableIntStateOf(0) }
        CollectEvents(events) { value = it }
        return value
    }
}

private class Opaque

private class OpaqueViewModel(handle: SavedStateHandle?) :
    MoleculeViewModel<Int, Int, Nothing>(handle) {
    @Composable
    override fun present(events: Flow<Int>): Int {
        val value by rememberSaveable { mutableStateOf(Opaque()) }
        check(value === value)
        return 0
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
    fun `rememberSaveable state survives process death through the handle`() = runTest(dispatcher) {
        val first = SaveableViewModel(SavedStateHandle()).tracked()
        first.state
        advanceUntilIdle()
        first.onEvent(7)
        advanceUntilIdle()

        val saved = first.presenterSavedState.performSave()
        store.clear()
        advanceUntilIdle()

        val second = SaveableViewModel(
            SavedStateHandle(mapOf(PresenterSavedState.KEY to saved)),
        ).tracked()

        assertThat(second.state.value).isEqualTo(7)
    }

    @Test
    fun `restoration happens at construction so any reader of state sees it`() = runTest(dispatcher) {
        val seed = SaveableViewModel(SavedStateHandle()).tracked()
        seed.state
        advanceUntilIdle()
        seed.onEvent(12)
        advanceUntilIdle()
        val saved = seed.presenterSavedState.performSave()
        store.clear()
        advanceUntilIdle()

        val restored = SaveableViewModel(
            SavedStateHandle(mapOf(PresenterSavedState.KEY to saved)),
        ).tracked()
        val firstReadAnywhere = restored.state.value

        assertThat(firstReadAnywhere).isEqualTo(12)
    }

    @Test
    fun `a ViewModel without a handle keeps rememberSaveable for its own lifetime`() =
        runTest(dispatcher) {
            val vm = SaveableViewModel(null).tracked()
            vm.state
            advanceUntilIdle()
            vm.onEvent(3)
            advanceUntilIdle()

            assertThat(vm.state.value).isEqualTo(3)
        }

    @Test
    fun `a value the handle cannot store fails at registration`() = runTest(dispatcher) {
        val vm = OpaqueViewModel(SavedStateHandle()).tracked()

        assertFailure { vm.state }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `corrupted restored state fails at construction`() {
        assertFailure {
            SaveableViewModel(SavedStateHandle(mapOf(PresenterSavedState.KEY to "garbage")))
        }.isInstanceOf(IllegalArgumentException::class)
    }
}
