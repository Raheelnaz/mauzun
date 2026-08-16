package io.github.raheelnaz.mauzun.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import io.github.raheelnaz.mauzun.MauzunViewModel
import io.github.raheelnaz.mauzun.collectAsStateWhileActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class EchoViewModel : MauzunViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var n by remember { mutableStateOf(0) }
        CollectEvents(events) { n = it }
        return n
    }
}

private class EffectEchoViewModel : MauzunViewModel<Int, Int, String>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        CollectEvents(events) { emitEffect("e$it") }
        return 0
    }
}

private class DisposalViewModel : MauzunViewModel<Int, Int, Nothing>() {
    var disposed = false

    @Composable
    override fun present(events: Flow<Int>): Int {
        DisposableEffect(Unit) { onDispose { disposed = true } }
        return 0
    }
}

private class ThrowingViewModel : MauzunViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int = error("boom in present")
}

private class TwoCollectorViewModel(
    private val seen: MutableList<String>,
) : MauzunViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        LaunchedEffect(Unit) { events.collect { seen += "first:$it" } }
        LaunchedEffect(Unit) { events.collect { seen += "second:$it" } }
        return 0
    }
}

private class LifecycleViewModel : MauzunViewModel<Nothing, Lifecycle, Nothing>() {
    @Composable
    override fun present(events: Flow<Nothing>): Lifecycle =
        LocalLifecycleOwner.current.lifecycle
}

private class WhileActiveViewModel(
    private val source: MutableStateFlow<Int>,
) : MauzunViewModel<Nothing, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Nothing>): Int {
        val value by source.collectAsStateWhileActive()
        return value
    }
}

class MauzunTestHarnessTest {

    @Test
    fun `presenters run with a resumed lifecycle`() = runTest {
        LifecycleViewModel().test {
            assertThat(awaitState().currentState).isEqualTo(Lifecycle.State.RESUMED)
        }
    }

    @Test
    fun `collectAsStateWhileActive needs no main dispatcher`() = runTest {
        val source = MutableStateFlow(1)
        WhileActiveViewModel(source).test {
            assertThat(awaitState()).isEqualTo(1)
            source.value = 2
            assertThat(awaitState()).isEqualTo(2)
        }
    }

    @Test
    fun `sendEvent completes its cascade before returning`() = runTest {
        EchoViewModel().test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(42)
            val sawTheChange = runCatching { expectNoStateChanges() }.isFailure
            assertThat(sawTheChange).isEqualTo(true)
        }
    }

    @Test
    fun `an event that changes nothing passes expectNoStateChanges`() = runTest {
        EchoViewModel().test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(0)
            expectNoStateChanges()
        }
    }

    @Test
    fun `events broadcast to every collector in the presenter`() = runTest {
        val seen = mutableListOf<String>()
        TwoCollectorViewModel(seen).test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(7)
            assertThat(seen).containsExactlyInAnyOrder("first:7", "second:7")
        }
    }

    @Test
    fun `unasserted states fail the test`() = runTest {
        val strict = runCatching {
            EchoViewModel().test {
                assertThat(awaitState()).isEqualTo(0)
                sendEvent(42)
            }
        }
        assertThat(strict.isFailure).isEqualTo(true)
    }

    @Test
    fun `awaitEffect returns effects in order`() = runTest {
        EffectEchoViewModel().test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(1)
            sendEvent(2)
            assertThat(awaitEffect()).isEqualTo("e1")
            assertThat(awaitEffect()).isEqualTo("e2")
        }
    }

    @Test
    fun `a presenter that emitted nothing passes expectNoEffects`() = runTest {
        EffectEchoViewModel().test {
            assertThat(awaitState()).isEqualTo(0)
            expectNoEffects()
        }
    }

    @Test
    fun `skipStates drops the models between`() = runTest {
        EchoViewModel().test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(1)
            sendEvent(2)
            sendEvent(3)
            skipStates(2)
            assertThat(awaitState()).isEqualTo(3)
        }
    }

    @Test
    fun `unasserted effects fail the test`() = runTest {
        val strict = runCatching {
            EffectEchoViewModel().test {
                assertThat(awaitState()).isEqualTo(0)
                sendEvent(42)
            }
        }
        assertThat(strict.isFailure).isEqualTo(true)
    }

    @Test
    fun `a presenter that throws on first composition fails the test with that failure`() = runTest {
        val outcome = runCatching {
            ThrowingViewModel().test {
                awaitState()
            }
        }
        val root = generateSequence(outcome.exceptionOrNull()) { it.cause }.lastOrNull()
        assertThat(root)
            .isNotNull()
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("boom in present")
    }

    @Test
    fun `awaitFailure returns the exception that ended the presenter`() = runTest {
        ThrowingViewModel().test {
            assertThat(awaitFailure())
                .isInstanceOf(IllegalStateException::class)
                .hasMessage("boom in present")
        }
    }

    @Test
    fun `the composition is disposed when the block completes`() = runTest {
        val vm = DisposalViewModel()
        vm.test {
            assertThat(awaitState()).isEqualTo(0)
        }
        assertThat(vm.disposed).isEqualTo(true)
    }

    @Test
    fun `the composition is disposed when the block throws`() = runTest {
        val vm = DisposalViewModel()
        runCatching {
            vm.test {
                assertThat(awaitState()).isEqualTo(0)
                error("assertion inside the block")
            }
        }
        assertThat(vm.disposed).isEqualTo(true)
    }
}
