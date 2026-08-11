package io.github.raheelnaz.mauzun.test

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelStore
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import io.github.raheelnaz.mauzun.MauzunViewModel
import io.github.raheelnaz.mauzun.binding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

private class CountingCompositionsViewModel : MauzunViewModel<Int, Int, String>() {
    var compositions = 0
    val received = mutableListOf<Int>()

    @Composable
    override fun present(events: Flow<Int>): Int {
        compositions++
        CollectEvents(events) { received += it }
        return 0
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BindingTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `binding returns the same instance every call`() {
        val vm = CountingCompositionsViewModel()
        store.put("vm", vm)

        assertThat(vm.testEntry().binding).isSameInstanceAs(vm.testEntry().binding)
    }

    @Test
    fun `reading effects does not start the presenter`() {
        val vm = CountingCompositionsViewModel()
        store.put("vm", vm)

        val entry = vm.testEntry()
        entry.binding.effects
        assertThat(vm.compositions).isEqualTo(0)

        entry.binding.state
        assertThat(vm.compositions).isEqualTo(1)
    }

    @Test
    fun `an event through the binding queues before the presenter starts`() {
        val vm = CountingCompositionsViewModel()
        store.put("vm", vm)

        val entry = vm.testEntry()
        entry.binding.onEvent(7)
        assertThat(vm.compositions).isEqualTo(0)

        entry.binding.state
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.compositions).isEqualTo(1)
        assertThat(vm.received).containsExactly(7)
    }
}
