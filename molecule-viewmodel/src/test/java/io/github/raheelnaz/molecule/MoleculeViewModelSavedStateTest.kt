package io.github.raheelnaz.molecule

import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import java.io.Serializable
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

private class SaveableViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var value by rememberSaveable { mutableIntStateOf(0) }
        CollectEvents(events) { value = it }
        return value
    }
}

private data class Filters(val query: String) : Serializable

private class FiltersViewModel : MoleculeViewModel<String, Filters, Nothing>() {
    @Composable
    override fun present(events: Flow<String>): Filters {
        var filters by rememberSaveable { mutableStateOf(Filters("")) }
        CollectEvents(events) { filters = Filters(it) }
        return filters
    }
}

private class TwoFieldViewModel : MoleculeViewModel<Int, Pair<Int, String>, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Pair<Int, String> {
        var count by rememberSaveable { mutableIntStateOf(0) }
        var label by rememberSaveable { mutableStateOf("start") }
        CollectEvents(events) {
            count = it
            label = "n$it"
        }
        return count to label
    }
}

private class OpaqueBox

private class OpaqueViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        val box = rememberSaveable { mutableStateOf(OpaqueBox()) }
        check(box.value === box.value)
        return 0
    }
}

private class LambdaViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        val block = rememberSaveable<() -> Int> { { 1 } }
        return block()
    }
}

private class SelfRetrievingViewModel(
    private val owner: ViewModelStoreOwner,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        moleculeViewModel<SelfRetrievingViewModel>(
            key = "self",
            viewModelStoreOwner = owner,
        )
        return 0
    }
}

private abstract class SwappableViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var value by rememberSaveable { mutableIntStateOf(0) }
        CollectEvents(events) { value = it }
        return value
    }
}

private class FirstImpl : SwappableViewModel()

private class SecondImpl : SwappableViewModel()

private class OwnHandleViewModel(
    val handle: SavedStateHandle,
) : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var value by rememberSaveable { mutableIntStateOf(0) }
        CollectEvents(events) { value = it }
        return value
    }
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

    fun <VM : MoleculeViewModel<*, *, *>> obtainWithoutMolecule(
        key: String,
        modelClass: Class<VM>,
        factory: ViewModelProvider.Factory,
    ): VM = ViewModelProvider(viewModelStore, factory, defaultViewModelCreationExtras)[
        key,
        modelClass,
    ]

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

private suspend inline fun <reified VM : MoleculeViewModel<*, *, *>> Host.obtain(
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
): PresenterEntry<VM> = moleculeFlow(RecompositionMode.Immediate) {
    moleculeViewModel<VM>(
        key = key,
        viewModelStoreOwner = this@obtain,
        factory = factory,
    )
}.first()

@OptIn(MoleculeViewModelAdapterApi::class)
private suspend inline fun <reified VM : MoleculeViewModel<*, *, *>> Host.attach(
    viewModel: VM,
    key: String? = null,
): PresenterEntry<VM> = moleculeFlow(RecompositionMode.Immediate) {
    moleculePresenterEntry(
        key = key,
        modelClass = VM::class.java,
        viewModelStoreOwner = this@attach,
        extras = defaultViewModelCreationExtras,
    ) { viewModel }
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoleculeViewModelSavedStateTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val saveableFactory = viewModelFactory {
        initializer { SaveableViewModel() }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `rememberSaveable survives process recreation without a handle constructor`() =
        runTest(dispatcher) {
            val firstHost = Host()
            val first = firstHost.obtain<SaveableViewModel>(factory = saveableFactory)
            assertThat(first.binding.state.value).isEqualTo(0)

            first.binding.onEvent(7)
            advanceUntilIdle()
            val saved = firstHost.save().parcelled()
            firstHost.destroy()
            firstHost.viewModelStore.clear()

            val secondHost = Host(saved)
            val second = secondHost.obtain<SaveableViewModel>(factory = saveableFactory)

            assertThat(second.binding.state.value).isEqualTo(7)
            secondHost.viewModelStore.clear()
        }

    @Test
    fun `a surviving ViewModel accepts the same holder after configuration change`() =
        runTest(dispatcher) {
            val store = ViewModelStore()
            val firstHost = Host(viewModelStore = store)
            val first = firstHost.obtain<SaveableViewModel>(factory = saveableFactory)
            first.binding.state
            first.binding.onEvent(4)
            advanceUntilIdle()

            val saved = firstHost.save().parcelled()
            firstHost.destroy()
            val secondHost = Host(saved, store)
            val second = secondHost.obtain<SaveableViewModel>(factory = saveableFactory)

            assertThat(second.viewModel).isSameInstanceAs(first.viewModel)
            assertThat(second.binding.state.value).isEqualTo(4)
            secondHost.viewModelStore.clear()
        }

    @Test
    fun `restored state can change and save again`() = runTest(dispatcher) {
        val firstHost = Host()
        val first = firstHost.obtain<SaveableViewModel>(factory = saveableFactory)
        first.binding.state
        first.binding.onEvent(3)
        advanceUntilIdle()
        val firstSaved = firstHost.save().parcelled()
        firstHost.destroy()
        firstHost.viewModelStore.clear()

        val secondHost = Host(firstSaved)
        val second = secondHost.obtain<SaveableViewModel>(factory = saveableFactory)
        assertThat(second.binding.state.value).isEqualTo(3)
        second.binding.onEvent(8)
        advanceUntilIdle()
        val secondSaved = secondHost.save().parcelled()
        secondHost.destroy()
        secondHost.viewModelStore.clear()

        val thirdHost = Host(secondSaved)
        val third = thirdHost.obtain<SaveableViewModel>(factory = saveableFactory)

        assertThat(third.binding.state.value).isEqualTo(8)
        thirdHost.viewModelStore.clear()
    }

    @Test
    fun `the helper is idempotent after the presenter starts`() = runTest(dispatcher) {
        val host = Host()
        val first = host.obtain<SaveableViewModel>(key = "counter", factory = saveableFactory)
        first.binding.state

        val second = host.attach(first.viewModel, key = "counter")

        assertThat(second.viewModel).isSameInstanceAs(first.viewModel)
        host.viewModelStore.clear()
    }

    @Test
    fun `an existing unstarted ViewModel can opt into saved state`() = runTest(dispatcher) {
        val host = Host()
        val existing = host.obtainWithoutMolecule(
            key = "counter",
            modelClass = SaveableViewModel::class.java,
            factory = saveableFactory,
        )

        val attached = host.obtain<SaveableViewModel>("counter", saveableFactory)

        assertThat(attached.viewModel).isEqualTo(existing)
        assertThat(attached.binding.state.value).isEqualTo(0)
        host.viewModelStore.clear()
    }

    @Test
    fun `late attachment fails instead of silently losing restoration`() = runTest(dispatcher) {
        val host = Host()
        val viewModel = host.obtainWithoutMolecule(
            key = "counter",
            modelClass = SaveableViewModel::class.java,
            factory = saveableFactory,
        )
        viewModel.bindingInstance.state

        assertFailure { host.obtain<SaveableViewModel>("counter", saveableFactory) }
            .isInstanceOf(IllegalStateException::class)
            .hasMessage(
                "rememberSaveable state was attached after the presenter started; " +
                    "obtain the presenter with moleculeViewModel() before its binding starts",
            )
        host.viewModelStore.clear()
    }

    @Test
    fun `restoration survives a different implementation of the requested class`() =
        runTest(dispatcher) {
            val firstHost = Host()
            val first = firstHost.obtain<SwappableViewModel>(
                factory = viewModelFactory { initializer<SwappableViewModel> { FirstImpl() } },
            )
            first.binding.state
            first.binding.onEvent(7)
            advanceUntilIdle()
            val saved = firstHost.save().parcelled()
            firstHost.destroy()
            firstHost.viewModelStore.clear()

            val secondHost = Host(saved)
            val second = secondHost.obtain<SwappableViewModel>(
                factory = viewModelFactory { initializer<SwappableViewModel> { SecondImpl() } },
            )

            assertThat(second.binding.state.value).isEqualTo(7)
            secondHost.viewModelStore.clear()
        }

    @Test
    fun `retrieval from inside present is rejected`() = runTest(dispatcher) {
        val host = Host()
        val ownerThatMustNotBeRead = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore
                get() = error("the ViewModelStore was touched")
        }
        val viewModel = SelfRetrievingViewModel(ownerThatMustNotBeRead)
        host.viewModelStore.put("presenter", viewModel)

        assertFailure { viewModel.bindingInstance.state }
            .isInstanceOf(IllegalStateException::class)
            .hasMessage("moleculeViewModel() cannot be called from MoleculeViewModel.present()")
        host.viewModelStore.clear()
    }

    @Test
    fun `different keys cannot attach different holders to one instance`() = runTest(dispatcher) {
        val host = Host()
        val viewModel = SaveableViewModel().also { host.viewModelStore.put("target", it) }
        host.attach(viewModel, key = "first")

        assertFailure { host.attach(viewModel, key = "second") }
            .isInstanceOf(IllegalStateException::class)
            .hasMessage(
                "rememberSaveable state was attached from a different ViewModelStoreOwner or key",
            )
        host.viewModelStore.clear()
    }

    @Test
    fun `explicit keys isolate instances through process recreation`() = runTest(dispatcher) {
        val firstHost = Host()
        val first = firstHost.obtain<SaveableViewModel>("first", saveableFactory)
        val second = firstHost.obtain<SaveableViewModel>("second", saveableFactory)
        first.binding.state
        second.binding.state
        first.binding.onEvent(3)
        second.binding.onEvent(9)
        advanceUntilIdle()

        val saved = firstHost.save().parcelled()
        firstHost.destroy()
        firstHost.viewModelStore.clear()
        val secondHost = Host(saved)
        val restoredFirst = secondHost.obtain<SaveableViewModel>("first", saveableFactory)
        val restoredSecond = secondHost.obtain<SaveableViewModel>("second", saveableFactory)

        assertThat(restoredFirst.binding.state.value).isEqualTo(3)
        assertThat(restoredSecond.binding.state.value).isEqualTo(9)
        secondHost.viewModelStore.clear()
    }

    @Test
    fun `serializable presenter state survives parceling`() = runTest(dispatcher) {
        val factory = viewModelFactory { initializer { FiltersViewModel() } }
        val firstHost = Host()
        val first = firstHost.obtain<FiltersViewModel>(factory = factory)
        first.binding.state
        first.binding.onEvent("vegan")
        advanceUntilIdle()

        val saved = firstHost.save().parcelled()
        firstHost.destroy()
        firstHost.viewModelStore.clear()
        val secondHost = Host(saved)
        val second = secondHost.obtain<FiltersViewModel>(factory = factory)

        assertThat(second.binding.state.value).isEqualTo(Filters("vegan"))
        secondHost.viewModelStore.clear()
    }

    @Test
    fun `rememberSaveable fields restore independently`() = runTest(dispatcher) {
        val factory = viewModelFactory { initializer { TwoFieldViewModel() } }
        val firstHost = Host()
        val first = firstHost.obtain<TwoFieldViewModel>(factory = factory)
        first.binding.state
        first.binding.onEvent(5)
        advanceUntilIdle()

        val saved = firstHost.save().parcelled()
        firstHost.destroy()
        firstHost.viewModelStore.clear()
        val secondHost = Host(saved)
        val second = secondHost.obtain<TwoFieldViewModel>(factory = factory)

        assertThat(second.binding.state.value).isEqualTo(5 to "n5")
        secondHost.viewModelStore.clear()
    }

    @Test
    fun `an app SavedStateHandle coexists with presenter saved state`() = runTest(dispatcher) {
        val factory = viewModelFactory {
            initializer { OwnHandleViewModel(createSavedStateHandle()) }
        }
        val firstHost = Host()
        val first = firstHost.obtain<OwnHandleViewModel>(factory = factory)
        first.viewModel.handle["selected-id"] = 42
        first.binding.state
        first.binding.onEvent(6)
        advanceUntilIdle()

        val saved = firstHost.save().parcelled()
        firstHost.destroy()
        firstHost.viewModelStore.clear()
        val secondHost = Host(saved)
        val second = secondHost.obtain<OwnHandleViewModel>(factory = factory)

        assertThat(second.viewModel.handle.get<Int>("selected-id")).isEqualTo(42)
        assertThat(second.binding.state.value).isEqualTo(6)
        secondHost.viewModelStore.clear()
    }

    @Test
    fun `plain ViewModel use keeps rememberSaveable fallback behavior`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val viewModel = OpaqueViewModel().also { store.put("target", it) }

        assertThat(viewModel.bindingInstance.state.value).isEqualTo(0)
        store.clear()
    }

    @Test
    fun `attached registry rejects values Android cannot save`() = runTest(dispatcher) {
        val host = Host()
        val factory = viewModelFactory { initializer { OpaqueViewModel() } }
        val viewModel = host.obtain<OpaqueViewModel>(factory = factory)

        assertFailure { viewModel.binding.state }.isInstanceOf(IllegalArgumentException::class)
        host.viewModelStore.clear()
    }

    @Test
    fun `attached registry rejects serializable lambdas`() = runTest(dispatcher) {
        val host = Host()
        val factory = viewModelFactory { initializer { LambdaViewModel() } }
        val viewModel = host.obtain<LambdaViewModel>(factory = factory)

        assertFailure { viewModel.binding.state }.isInstanceOf(IllegalArgumentException::class)
        host.viewModelStore.clear()
    }

    @Test
    fun `the holder key prefix is rejected as a presenter key`() = runTest(dispatcher) {
        val host = Host()

        assertFailure {
            host.obtain<SaveableViewModel>(
                key = "io.github.raheelnaz.molecule.presenter-saved-state-holder:screen",
                factory = saveableFactory,
            )
        }
            .isInstanceOf(IllegalArgumentException::class)
            .hasMessage(
                "ViewModel keys starting with " +
                    "io.github.raheelnaz.molecule.presenter-saved-state-holder: are reserved",
            )
        host.viewModelStore.clear()
    }

    @Test
    fun `a rejected key leaves other holders untouched`() = runTest(dispatcher) {
        val host = Host()
        val screen = host.obtain<SaveableViewModel>("screen", saveableFactory)
        screen.binding.state

        runCatching {
            host.obtain<SaveableViewModel>(
                key = "io.github.raheelnaz.molecule.presenter-saved-state-holder:screen",
                factory = saveableFactory,
            )
        }

        // A replaced holder would make this second retrieval throw.
        val again = host.obtain<SaveableViewModel>("screen", saveableFactory)
        assertThat(again.viewModel).isSameInstanceAs(screen.viewModel)
        host.viewModelStore.clear()
    }

    @Test
    fun `an owner without saved state support names the problem`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val bare = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore get() = store
        }

        val outcome = runCatching {
            moleculeFlow(RecompositionMode.Immediate) {
                moleculeViewModel<SaveableViewModel>(
                    viewModelStoreOwner = bare,
                    factory = saveableFactory,
                )
            }.first()
        }

        val named = generateSequence(outcome.exceptionOrNull()) { it.cause }
            .firstOrNull { it.message?.startsWith("moleculeViewModel needs") == true }
        assertThat(named)
            .isNotNull()
            .isInstanceOf(IllegalStateException::class)
            .hasMessage(
                "moleculeViewModel needs a ViewModelStoreOwner that provides saved state. " +
                    "An Activity, Fragment, or navigation entry does; this owner does not.",
            )
        store.clear()
    }

    @Test
    fun `a malformed saved-state envelope is ignored`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(PresenterSavedState.KEY to "garbage"))
        val savedState = PresenterSavedState(handle)

        assertThat(savedState.registry.performSave()).isEqualTo(emptyMap())
    }
}
