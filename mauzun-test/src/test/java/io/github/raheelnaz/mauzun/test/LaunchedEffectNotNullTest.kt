package io.github.raheelnaz.mauzun.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import io.github.raheelnaz.mauzun.LaunchedEffectNotNull
import io.github.raheelnaz.mauzun.MauzunViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

private data class SetTarget(val value: String?)

private class GuardedViewModel(
    private val log: MutableList<String>,
) : MauzunViewModel<SetTarget, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<SetTarget>): Int {
        var target by remember { mutableStateOf<String?>(null) }
        var runs by remember { mutableIntStateOf(0) }

        CollectEvents(events) { target = it.value }

        LaunchedEffectNotNull(target) { t ->
            log += "start:$t"
            runs++
            try {
                awaitCancellation()
            } finally {
                log += "cancel:$t"
            }
        }
        return runs
    }
}

private data class SetBoth(val a: String?, val b: Int?)

private class PairedViewModel(
    private val log: MutableList<String>,
) : MauzunViewModel<SetBoth, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<SetBoth>): Int {
        var a by remember { mutableStateOf<String?>(null) }
        var b by remember { mutableStateOf<Int?>(null) }
        var runs by remember { mutableIntStateOf(0) }

        CollectEvents(events) { a = it.a; b = it.b }

        LaunchedEffectNotNull(a, b) { x, y ->
            log += "$x:$y"
            runs++
        }
        return runs
    }
}

class LaunchedEffectNotNullTest {

    @Test
    fun `runs only while the value is non-null and cancels when it goes back`() = runTest {
        val log = mutableListOf<String>()
        GuardedViewModel(log).test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(SetTarget(null))
            expectNoStateChanges()
            assertThat(log).isEqualTo(emptyList<String>())

            sendEvent(SetTarget("a"))
            assertThat(awaitState()).isEqualTo(1)
            assertThat(log).containsExactly("start:a")

            sendEvent(SetTarget(null))
            expectNoStateChanges()
            assertThat(log).containsExactly("start:a", "cancel:a")
        }
    }

    @Test
    fun `a change restarts the effect`() = runTest {
        val log = mutableListOf<String>()
        GuardedViewModel(log).test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(SetTarget("a"))
            assertThat(awaitState()).isEqualTo(1)

            sendEvent(SetTarget("b"))
            assertThat(awaitState()).isEqualTo(2)
            assertThat(log).containsExactly("start:a", "cancel:a", "start:b")
        }
    }

    @Test
    fun `two values run only when both are non-null`() = runTest {
        val log = mutableListOf<String>()
        PairedViewModel(log).test {
            assertThat(awaitState()).isEqualTo(0)
            sendEvent(SetBoth(a = "x", b = null))
            expectNoStateChanges()

            sendEvent(SetBoth(a = "x", b = 1))
            assertThat(awaitState()).isEqualTo(1)
            assertThat(log).containsExactly("x:1")
        }
    }
}
