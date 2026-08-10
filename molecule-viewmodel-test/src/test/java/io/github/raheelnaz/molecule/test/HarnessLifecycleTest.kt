package io.github.raheelnaz.molecule.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.raheelnaz.molecule.MoleculeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

private class StreamingViewModel(
    private val source: MutableStateFlow<Int>,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        val n by source.collectAsStateWithLifecycle()
        return n
    }
}

private class CountingViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var n by remember { mutableIntStateOf(0) }
        CollectEvents(events) { n = it }
        return n
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessLifecycleTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `lifecycle aware collection pauses at CREATED and resumes`() = runTest {
        val source = MutableStateFlow(0)
        StreamingViewModel(source).test {
            assertThat(awaitState()).isEqualTo(0)
            source.value = 1
            assertThat(awaitState()).isEqualTo(1)

            moveToState(Lifecycle.State.CREATED)
            source.value = 2
            expectNoStateChanges()

            moveToState(Lifecycle.State.RESUMED)
            assertThat(awaitState()).isEqualTo(2)
        }
    }

    @Test
    fun `the initial lifecycle state holds until moved`() = runTest {
        val source = MutableStateFlow(0)
        StreamingViewModel(source).test(initialLifecycleState = Lifecycle.State.CREATED) {
            assertThat(awaitState()).isEqualTo(0)
            source.value = 1
            expectNoStateChanges()

            moveToState(Lifecycle.State.RESUMED)
            assertThat(awaitState()).isEqualTo(1)
        }
    }

    @Test
    fun `a presenter that ignores lifecycle is unaffected`() = runTest {
        CountingViewModel().test {
            assertThat(awaitState()).isEqualTo(0)
            moveToState(Lifecycle.State.CREATED)
            sendEvent(5)
            assertThat(awaitState()).isEqualTo(5)
        }
    }
}
