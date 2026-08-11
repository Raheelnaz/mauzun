package io.github.raheelnaz.molecule.metro

import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import io.github.raheelnaz.molecule.MoleculeViewModel
import io.github.raheelnaz.molecule.PresenterEntry
import io.github.raheelnaz.molecule.binding
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class SaveableMetroViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var value by rememberSaveable { mutableIntStateOf(0) }
        CollectEvents(events) { value = it }
        return value
    }
}

private class AutomaticAssistedViewModel(
    val route: String,
) : MoleculeViewModel<Nothing, String, Nothing>() {
    @Composable
    override fun present(events: Flow<Nothing>): String = route
}

private class ManualAssistedViewModel(
    val route: String,
) : MoleculeViewModel<Nothing, String, Nothing>() {
    @Composable
    override fun present(events: Flow<Nothing>): String = route
}

private class SaveableAssistedViewModel(
    val route: String,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var value by rememberSaveable { mutableIntStateOf(0) }
        CollectEvents(events) { value = it }
        return value
    }
}

private interface SaveableManualFactory : ManualViewModelAssistedFactory {
    fun create(route: String): SaveableAssistedViewModel
}

private class SaveableManualFactoryImpl : SaveableManualFactory {
    override fun create(route: String): SaveableAssistedViewModel =
        SaveableAssistedViewModel(route)
}

private interface TestManualFactory : ManualViewModelAssistedFactory {
    fun create(route: String): ManualAssistedViewModel
}

private class TestManualFactoryImpl : TestManualFactory {
    override fun create(route: String): ManualAssistedViewModel = ManualAssistedViewModel(route)
}

private class TestMetroFactory(
    standardProviders: Map<KClass<out ViewModel>, () -> ViewModel> = emptyMap(),
    assistedProviders: Map<KClass<out ViewModel>, () -> ViewModelAssistedFactory> = emptyMap(),
    manualProviders: Map<
        KClass<out ManualViewModelAssistedFactory>,
        () -> ManualViewModelAssistedFactory,
    > = emptyMap(),
) : MetroViewModelFactory() {
    override val viewModelProviders = standardProviders
    override val assistedFactoryProviders = assistedProviders
    override val manualAssistedFactoryProviders = manualProviders
}

private class Host(
    restoredState: Bundle? = null,
    override val viewModelStore: ViewModelStore = ViewModelStore(),
) : SavedStateRegistryOwner, ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    private val lifecycleRegistry = LifecycleRegistry.createUnsafe(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        ViewModelProvider.NewInstanceFactory()
    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras().apply {
            set(SAVED_STATE_REGISTRY_OWNER_KEY, this@Host)
            set(VIEW_MODEL_STORE_OWNER_KEY, this@Host)
        }

    init {
        enableSavedStateHandles()
        controller.performRestore(restoredState)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun save(): Bundle = Bundle().also(controller::performSave)

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

@Composable
@OptIn(InternalComposeApi::class)
private fun <T> withCompositionLocals(
    values: Array<ProvidedValue<*>>,
    content: @Composable () -> T,
): T {
    val composer = currentComposer
    composer.startProviders(values)
    val result = content()
    composer.endProviders()
    return result
}

private suspend inline fun <reified VM : MoleculeViewModel<*, *, *>> Host.obtainStandard(
    factory: MetroViewModelFactory,
    key: String? = null,
): PresenterEntry<VM> = moleculeFlow(RecompositionMode.Immediate) {
    withCompositionLocals(arrayOf(LocalMetroViewModelFactory provides factory)) {
        metroMoleculeViewModel<VM>(
            viewModelStoreOwner = this@obtainStandard,
            key = key,
        )
    }
}.first()

private suspend inline fun <reified VM : MoleculeViewModel<*, *, *>> Host.obtainAssisted(
    factory: MetroViewModelFactory,
    key: String? = null,
    extras: CreationExtras = defaultViewModelCreationExtras,
): PresenterEntry<VM> = moleculeFlow(RecompositionMode.Immediate) {
    withCompositionLocals(arrayOf(LocalMetroViewModelFactory provides factory)) {
        assistedMetroMoleculeViewModel<VM>(
            viewModelStoreOwner = this@obtainAssisted,
            key = key,
            extras = extras,
        )
    }
}.first()

private suspend inline fun <
    reified VM : MoleculeViewModel<*, *, *>,
    reified FactoryType : ManualViewModelAssistedFactory,
> Host.obtainManuallyAssisted(
    factory: MetroViewModelFactory,
    key: String? = null,
    extras: CreationExtras = defaultViewModelCreationExtras,
    crossinline createViewModel: FactoryType.(CreationExtras) -> VM,
): PresenterEntry<VM> = moleculeFlow(RecompositionMode.Immediate) {
    withCompositionLocals(arrayOf(LocalMetroViewModelFactory provides factory)) {
        assistedMetroMoleculeViewModel<VM, FactoryType>(
            viewModelStoreOwner = this@obtainManuallyAssisted,
            key = key,
            extras = extras,
            createViewModel = createViewModel,
        )
    }
}.first()

private fun Bundle.parcelled(): Bundle {
    val parcel = Parcel.obtain()
    return try {
        parcel.writeBundle(this)
        parcel.setDataPosition(0)
        requireNotNull(parcel.readBundle(Host::class.java.classLoader))
    } finally {
        parcel.recycle()
    }
}

private val routeKey = object : CreationExtras.Key<String> {}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetroMoleculeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `standard retrieval returns one entry for one owner and key`() = runTest(dispatcher) {
        var creations = 0
        val factory = TestMetroFactory(
            standardProviders = mapOf(
                SaveableMetroViewModel::class to {
                    creations++
                    SaveableMetroViewModel()
                },
            ),
        )
        val host = Host()

        val first = host.obtainStandard<SaveableMetroViewModel>(factory)
        val second = host.obtainStandard<SaveableMetroViewModel>(factory)

        assertThat(first.binding).isSameInstanceAs(second.binding)
        assertThat(first.binding.state.value).isEqualTo(0)
        assertThat(creations).isEqualTo(1)
        host.viewModelStore.clear()
    }

    @Test
    fun `standard retrieval restores rememberSaveable after process recreation`() =
        runTest(dispatcher) {
            val factory = TestMetroFactory(
                standardProviders = mapOf(
                    SaveableMetroViewModel::class to { SaveableMetroViewModel() },
                ),
            )
            val firstHost = Host()
            val first = firstHost.obtainStandard<SaveableMetroViewModel>(factory)
            first.binding.state
            first.binding.onEvent(7)
            advanceUntilIdle()

            val saved = firstHost.save().parcelled()
            firstHost.destroy()
            firstHost.viewModelStore.clear()

            val secondHost = Host(saved)
            val second = secondHost.obtainStandard<SaveableMetroViewModel>(factory)

            assertThat(second.binding.state.value).isEqualTo(7)
            secondHost.viewModelStore.clear()
        }

    @Test
    fun `automatic assisted retrieval forwards creation extras`() = runTest(dispatcher) {
        val host = Host()
        val extras = MutableCreationExtras(host.defaultViewModelCreationExtras).apply {
            set(routeKey, "account/42")
        }
        var receivedExtras: CreationExtras? = null
        val factory = TestMetroFactory(
            assistedProviders = mapOf(
                AutomaticAssistedViewModel::class to {
                    object : ViewModelAssistedFactory {
                        override fun create(extras: CreationExtras): ViewModel {
                            receivedExtras = extras
                            extras.createSavedStateHandle()
                            return AutomaticAssistedViewModel(checkNotNull(extras[routeKey]))
                        }
                    }
                },
            ),
        )

        val entry = host.obtainAssisted<AutomaticAssistedViewModel>(factory, extras = extras)

        val forwardedExtras = checkNotNull(receivedExtras)
        assertThat(forwardedExtras[routeKey]).isEqualTo("account/42")
        assertThat(forwardedExtras[SAVED_STATE_REGISTRY_OWNER_KEY]).isSameInstanceAs(host)
        assertThat(forwardedExtras[VIEW_MODEL_STORE_OWNER_KEY]).isSameInstanceAs(host)
        assertThat(entry.binding.state.value).isEqualTo("account/42")
        host.viewModelStore.clear()
    }

    @Test
    fun `manual assisted retrieval uses Metro factory and supplied arguments`() =
        runTest(dispatcher) {
            val host = Host()
            val extras = MutableCreationExtras(host.defaultViewModelCreationExtras).apply {
                set(routeKey, "card/9")
            }
            var receivedExtras: CreationExtras? = null
            val factory = TestMetroFactory(
                manualProviders = mapOf(
                    TestManualFactory::class to { TestManualFactoryImpl() },
                ),
            )

            val entry = host.obtainManuallyAssisted<ManualAssistedViewModel, TestManualFactory>(
                factory = factory,
                extras = extras,
            ) {
                receivedExtras = it
                create(checkNotNull(it[routeKey]))
            }

            val forwardedExtras = checkNotNull(receivedExtras)
            assertThat(forwardedExtras[routeKey]).isEqualTo("card/9")
            assertThat(forwardedExtras[SAVED_STATE_REGISTRY_OWNER_KEY]).isSameInstanceAs(host)
            assertThat(forwardedExtras[VIEW_MODEL_STORE_OWNER_KEY]).isSameInstanceAs(host)
            assertThat(entry.binding.state.value).isEqualTo("card/9")
            host.viewModelStore.clear()
        }

    @Test
    fun `manual assisted retrieval restores rememberSaveable after process recreation`() =
        runTest(dispatcher) {
            val factory = TestMetroFactory(
                manualProviders = mapOf(
                    SaveableManualFactory::class to { SaveableManualFactoryImpl() },
                ),
            )
            val firstHost = Host()
            val first = firstHost
                .obtainManuallyAssisted<SaveableAssistedViewModel, SaveableManualFactory>(
                    factory = factory,
                ) { create("route/1") }
            first.binding.state
            first.binding.onEvent(6)
            advanceUntilIdle()

            val saved = firstHost.save().parcelled()
            firstHost.destroy()
            firstHost.viewModelStore.clear()

            val secondHost = Host(saved)
            val second = secondHost
                .obtainManuallyAssisted<SaveableAssistedViewModel, SaveableManualFactory>(
                    factory = factory,
                ) { create("route/1") }

            assertThat(second.binding.state.value).isEqualTo(6)
            secondHost.viewModelStore.clear()
        }

    @Test
    fun `explicit keys isolate Metro ViewModels`() = runTest(dispatcher) {
        var creations = 0
        val factory = TestMetroFactory(
            standardProviders = mapOf(
                SaveableMetroViewModel::class to {
                    creations++
                    SaveableMetroViewModel()
                },
            ),
        )
        val host = Host()

        val first = host.obtainStandard<SaveableMetroViewModel>(factory, key = "first")
        val second = host.obtainStandard<SaveableMetroViewModel>(factory, key = "second")
        first.binding.state
        second.binding.state
        first.binding.onEvent(3)
        second.binding.onEvent(8)
        advanceUntilIdle()

        assertThat(first.binding.state.value).isEqualTo(3)
        assertThat(second.binding.state.value).isEqualTo(8)
        assertThat(creations).isEqualTo(2)
        host.viewModelStore.clear()
    }

    @Test
    fun `default owner comes from composition`() = runTest(dispatcher) {
        val host = Host()
        val factory = TestMetroFactory(
            standardProviders = mapOf(
                SaveableMetroViewModel::class to { SaveableMetroViewModel() },
            ),
        )

        val entry = moleculeFlow(RecompositionMode.Immediate) {
            withCompositionLocals(
                arrayOf(
                    LocalMetroViewModelFactory provides factory,
                    LocalViewModelStoreOwner provides host,
                ),
            ) {
                metroMoleculeViewModel<SaveableMetroViewModel>()
            }
        }.first()

        assertThat(entry.binding.state.value).isEqualTo(0)
        host.viewModelStore.clear()
    }

    @Test
    fun `reserved key fails before Metro reads its factory`() = runTest(dispatcher) {
        val host = Host()

        assertFailure {
            moleculeFlow(RecompositionMode.Immediate) {
                metroMoleculeViewModel<SaveableMetroViewModel>(
                    viewModelStoreOwner = host,
                    key = "io.github.raheelnaz.molecule.presenter-saved-state-holder:screen",
                )
            }.first()
        }
            .isInstanceOf(IllegalArgumentException::class)
            .hasMessage(
                "ViewModel keys starting with " +
                    "io.github.raheelnaz.molecule.presenter-saved-state-holder: are reserved",
            )
        host.viewModelStore.clear()
    }
}
