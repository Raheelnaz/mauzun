package io.github.raheelnaz.mauzun.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import io.github.raheelnaz.mauzun.MauzunViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

private sealed interface FilterEvent
private data class Add(val value: Int) : FilterEvent
private data object Ping : FilterEvent

private class FilteringViewModel(
    private val log: MutableList<String>,
) : MauzunViewModel<FilterEvent, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<FilterEvent>): Int {
        var sum by remember { mutableIntStateOf(0) }
        CollectEventsOf<Add>(events) { sum += it.value }
        CollectEventsOf<Ping>(events) { log += "ping" }
        return sum
    }
}

class CollectEventsOfTest {

    @Test
    fun `only events of the type reach the handler`() = runTest {
        FilteringViewModel(mutableListOf()).test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(Add(2))
            assertThat(awaitState()).isEqualTo(2)
            sendEvent(Ping)
            expectNoStateChanges()
            sendEvent(Add(3))
            assertThat(awaitState()).isEqualTo(5)
        }
    }

    @Test
    fun `typed collectors split the stream by type`() = runTest {
        val log = mutableListOf<String>()
        FilteringViewModel(log).test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(Ping)
            expectNoStateChanges()
            assertThat(log).containsExactly("ping")
            sendEvent(Add(1))
            assertThat(awaitState()).isEqualTo(1)
            assertThat(log).containsExactly("ping")
        }
    }
}
