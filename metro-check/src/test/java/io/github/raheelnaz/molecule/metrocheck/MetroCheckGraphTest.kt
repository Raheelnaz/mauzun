package io.github.raheelnaz.molecule.metrocheck

import androidx.lifecycle.viewmodel.CreationExtras
import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.metro.createGraph
import org.junit.Test

class MetroCheckGraphTest {

    @Test
    fun `the contributed ViewModel reaches the provider map`() {
        val graph = createGraph<MetroCheckGraph>()

        val provider = graph.viewModelProviders[CheckViewModel::class]
        assertThat(provider?.invoke() is CheckViewModel).isEqualTo(true)
    }

    @Test
    fun `the contributed automatic factory reaches the assisted map`() {
        val graph = createGraph<MetroCheckGraph>()

        val viewModel = graph.assistedFactoryProviders[CheckExtrasViewModel::class]
            ?.invoke()
            ?.create(CreationExtras.Empty) as CheckExtrasViewModel
        assertThat(viewModel.id).isEqualTo("from-extras")
    }

    @Test
    fun `the contributed factory reaches the manual map and builds the ViewModel`() {
        val graph = createGraph<MetroCheckGraph>()

        val factory = graph.manualAssistedFactoryProviders[CheckAssistedViewModel.Factory::class]
            ?.invoke() as CheckAssistedViewModel.Factory
        assertThat(factory.create("route/7").argument).isEqualTo("route/7")
    }
}
