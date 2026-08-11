package io.github.raheelnaz.mauzun

import androidx.compose.runtime.Composable
import assertk.assertThat
import assertk.assertions.isSameInstanceAs
import kotlinx.coroutines.flow.Flow
import org.junit.Test

private class InferenceViewModel : MauzunViewModel<Int, String, Long>() {
    @Composable
    override fun present(events: Flow<Int>): String = "model"
}

class PresenterEntryInferenceTest {
    @Test
    fun `binding types are inferred from the entry ViewModel`() {
        val viewModel = InferenceViewModel()
        val entry = PresenterEntry.create(viewModel)
        val binding: PresenterBinding<Int, String, Long> = entry.binding

        assertThat(binding).isSameInstanceAs(entry.binding)
        assertThat(PresenterEntry.create(viewModel).binding).isSameInstanceAs(entry.binding)
    }
}

@Composable
@Suppress("unused")
private fun hostInferenceShape() {
    PresenterHost(
        presenter = mauzunViewModel<InferenceViewModel>(),
        onEffect = { effect ->
            val typed: Long = effect
            check(typed >= 0)
        },
    ) { model, onEvent ->
        val typed: String = model
        onEvent(typed.length)
    }
}
