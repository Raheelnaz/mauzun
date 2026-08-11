package io.github.raheelnaz.mauzun.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import io.github.raheelnaz.mauzun.MauzunViewModel
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

private class TwoHandlerViewModel(private val boom: () -> Nothing) :
    MauzunViewModel<Int, Int, Nothing>() {
    val thrower = mutableListOf<Int>()
    val writer = mutableListOf<Int>()

    @Composable
    override fun present(events: Flow<Int>): Int {
        var n by remember { mutableIntStateOf(0) }
        CollectEvents(events) {
            thrower += it
            if (it == 2) boom()
        }
        CollectEvents(events) {
            writer += it
            n = it
        }
        return n
    }
}

private class TimeoutViewModel(private val guarded: Boolean) :
    MauzunViewModel<Int, Int, Nothing>() {
    val handled = mutableListOf<Int>()

    @Composable
    override fun present(events: Flow<Int>): Int {
        var n by remember { mutableIntStateOf(0) }
        CollectEvents(events) { event ->
            if (guarded) {
                try {
                    withTimeout(10) { delay(1_000) }
                } catch (t: Throwable) {
                    currentCoroutineContext().ensureActive()
                    handled += event
                }
            } else {
                withTimeout(10) { delay(1_000) }
            }
            n = event
        }
        return n
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PresenterFailureTest {

    @Test
    fun `an exception from a handler stops every collector`() = runTest {
        val vm = TwoHandlerViewModel { throw IOException("network") }
        val outcome = runCatching {
            vm.test {
                assertThat(awaitState()).isEqualTo(0)
                sendEvent(1)
                assertThat(awaitState()).isEqualTo(1)
                sendEvent(2)
                sendEvent(3)
                awaitState()
            }
        }

        val root = generateSequence(outcome.exceptionOrNull()) { it.cause }.lastOrNull()
        assertThat(root).isNotNull().isInstanceOf(IOException::class).hasMessage("network")
        assertThat(vm.thrower).containsExactly(1, 2)
        assertThat(vm.writer).containsExactly(1)
    }

    @Test
    fun `a cancellation from a handler takes only that collector`() = runTest {
        val vm = TwoHandlerViewModel { throw CancellationException("spurious") }
        vm.test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(1)
            assertThat(awaitState()).isEqualTo(1)
            sendEvent(2)
            assertThat(awaitState()).isEqualTo(2)
            sendEvent(3)
            assertThat(awaitState()).isEqualTo(3)
        }

        assertThat(vm.thrower).containsExactly(1, 2)
        assertThat(vm.writer).containsExactly(1, 2, 3)
    }

    @Test
    fun `a timeout that escapes a handler stops it silently`() = runTest {
        val scope = this
        val vm = TimeoutViewModel(guarded = false)
        vm.test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(1)
            scope.advanceUntilIdle()
            sendEvent(2)
            scope.advanceUntilIdle()

            expectNoStateChanges()
        }
    }

    @Test
    fun `ensureActive lets a handler recover from a timeout`() = runTest {
        val scope = this
        val vm = TimeoutViewModel(guarded = true)
        vm.test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(1)
            scope.advanceUntilIdle()
            assertThat(awaitState()).isEqualTo(1)
            sendEvent(2)
            scope.advanceUntilIdle()
            assertThat(awaitState()).isEqualTo(2)
        }

        assertThat(vm.handled).containsExactly(1, 2)
    }
}
