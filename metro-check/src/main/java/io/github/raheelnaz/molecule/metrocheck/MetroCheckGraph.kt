package io.github.raheelnaz.molecule.metrocheck

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import io.github.raheelnaz.molecule.MoleculeViewModel
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@DependencyGraph(AppScope::class)
interface MetroCheckGraph : ViewModelGraph

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class CheckViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
    override val assistedFactoryProviders:
        Map<KClass<out ViewModel>, () -> ViewModelAssistedFactory>,
    override val manualAssistedFactoryProviders:
        Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>,
) : MetroViewModelFactory()

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding<ViewModel>())
class CheckViewModel : MoleculeViewModel<Int, Int, Nothing>() {

    @Composable
    override fun present(events: Flow<Int>): Int = 0
}

@AssistedInject
class CheckExtrasViewModel(
    @Assisted val id: String,
) : MoleculeViewModel<Int, Int, Nothing>() {

    @AssistedFactory
    @ViewModelAssistedFactoryKey(CheckExtrasViewModel::class)
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ViewModelAssistedFactory {
        override fun create(extras: CreationExtras): CheckExtrasViewModel = create("from-extras")

        fun create(id: String): CheckExtrasViewModel
    }

    @Composable
    override fun present(events: Flow<Int>): Int = 0
}

@AssistedInject
class CheckAssistedViewModel(
    @Assisted val argument: String,
) : MoleculeViewModel<Int, Int, Nothing>() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    fun interface Factory : ManualViewModelAssistedFactory {
        fun create(argument: String): CheckAssistedViewModel
    }

    @Composable
    override fun present(events: Flow<Int>): Int = 0
}
