package io.github.raheelnaz.molecule.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import io.github.raheelnaz.molecule.MoleculeViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class EchoViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var n by remember { mutableStateOf(0) }
        CollectEvents(events) { n = it }
        return n
    }
}

private class TwoCollectorViewModel(
    private val seen: MutableList<String>,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        LaunchedEffect(Unit) { events.collect { seen += "first:$it" } }
        LaunchedEffect(Unit) { events.collect { seen += "second:$it" } }
        return 0
    }
}

class MoleculeTestHarnessTest {

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
}
