package io.github.raheelnaz.molecule

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelStore
import assertk.assertFailure
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.flow.Flow
import org.junit.Test

private class InertViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int = 0
}

class MoleculeViewModelTest {

    @Test
    fun `event 51 with nothing draining overflows loudly`() {
        val vm = InertViewModel()
        repeat(50) { vm.onEvent(it) }
        assertFailure { vm.onEvent(50) }.isInstanceOf(IllegalStateException::class)
    }

    @Test
    fun `events after onCleared are dropped, not crashes`() {
        val store = ViewModelStore()
        val vm = InertViewModel()
        store.put("vm", vm)
        store.clear()
        repeat(120) { vm.onEvent(it) }
    }
}
