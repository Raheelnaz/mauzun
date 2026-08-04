package io.github.raheelnaz.molecule

import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

private class SaveableViewModel(handle: SavedStateHandle) :
    MoleculeViewModel<Int, Int, Nothing>(handle) {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var value by rememberSaveable { mutableIntStateOf(0) }
        CollectEvents(events) { value = it }
        return value
    }
}

private class TwoFieldViewModel(handle: SavedStateHandle) :
    MoleculeViewModel<Int, Pair<Int, String>, Nothing>(handle) {
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

private data class Filters(val query: String) : Serializable

private class FiltersViewModel(handle: SavedStateHandle) :
    MoleculeViewModel<String, Filters, Nothing>(handle) {
    @Composable
    override fun present(events: Flow<String>): Filters {
        var filters by rememberSaveable { mutableStateOf(Filters("")) }
        CollectEvents(events) { filters = Filters(it) }
        return filters
    }
}

private class KeepHandleViewModel(val handle: SavedStateHandle) :
    MoleculeViewModel<Int, Int, Nothing>(handle) {
    @Composable
    override fun present(events: Flow<Int>): Int {
        var value by rememberSaveable { mutableIntStateOf(0) }
        CollectEvents(events) { value = it }
        return value
    }
}

private class Host(restored: Bundle?) : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry.createUnsafe(this)
    private val controller = SavedStateRegistryController.create(this)

    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    init {
        enableSavedStateHandles()
        controller.performRestore(restored)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun save(): Bundle = Bundle().also { controller.performSave(it) }

    fun <VM : ViewModel> create(key: String, modelClass: Class<VM>, factory: ViewModelProvider.Factory): VM {
        val extras = MutableCreationExtras().apply {
            set(SAVED_STATE_REGISTRY_OWNER_KEY, this@Host)
            set(VIEW_MODEL_STORE_OWNER_KEY, this@Host)
        }
        return ViewModelProvider(viewModelStore, factory, extras)[key, modelClass]
    }
}

private fun Bundle.parcelled(): Bundle {
    val parcel = Parcel.obtain()
    parcel.writeBundle(this)
    parcel.setDataPosition(0)
    val result = requireNotNull(parcel.readBundle(Host::class.java.classLoader))
    parcel.recycle()
    return result
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedStateRestorationTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val saveableFactory = viewModelFactory {
        initializer { SaveableViewModel(createSavedStateHandle()) }
    }

    @Test
    fun `a primitive survives owner save, parceling, and recreation`() = runTest(dispatcher) {
        val host = Host(null)
        val vm = host.create("vm", SaveableViewModel::class.java, saveableFactory)
        vm.state
        advanceUntilIdle()
        vm.onEvent(7)
        advanceUntilIdle()

        val saved = host.save().parcelled()
        host.viewModelStore.clear()

        val next = Host(saved).create("vm", SaveableViewModel::class.java, saveableFactory)

        assertThat(next.state.value).isEqualTo(7)
    }

    @Test
    fun `restore then mutate then save again keeps the newest value`() = runTest(dispatcher) {
        val first = Host(null)
        val vm = first.create("vm", SaveableViewModel::class.java, saveableFactory)
        vm.state
        advanceUntilIdle()
        vm.onEvent(7)
        advanceUntilIdle()
        val saved = first.save().parcelled()
        first.viewModelStore.clear()

        val second = Host(saved)
        val restored = second.create("vm", SaveableViewModel::class.java, saveableFactory)
        restored.state
        advanceUntilIdle()
        restored.onEvent(9)
        advanceUntilIdle()
        val savedAgain = second.save().parcelled()
        second.viewModelStore.clear()

        val third = Host(savedAgain).create("vm", SaveableViewModel::class.java, saveableFactory)

        assertThat(third.state.value).isEqualTo(9)
    }

    @Test
    fun `two rememberSaveable fields restore independently`() = runTest(dispatcher) {
        val factory = viewModelFactory {
            initializer { TwoFieldViewModel(createSavedStateHandle()) }
        }
        val host = Host(null)
        val vm = host.create("vm", TwoFieldViewModel::class.java, factory)
        vm.state
        advanceUntilIdle()
        vm.onEvent(5)
        advanceUntilIdle()

        val saved = host.save().parcelled()
        host.viewModelStore.clear()

        val next = Host(saved).create("vm", TwoFieldViewModel::class.java, factory)

        assertThat(next.state.value).isEqualTo(5 to "n5")
    }

    @Test
    fun `a serializable value survives parceling`() = runTest(dispatcher) {
        val factory = viewModelFactory {
            initializer { FiltersViewModel(createSavedStateHandle()) }
        }
        val host = Host(null)
        val vm = host.create("vm", FiltersViewModel::class.java, factory)
        vm.state
        advanceUntilIdle()
        vm.onEvent("vegan")
        advanceUntilIdle()

        val saved = host.save().parcelled()
        host.viewModelStore.clear()

        val next = Host(saved).create("vm", FiltersViewModel::class.java, factory)

        assertThat(next.state.value).isEqualTo(Filters("vegan"))
    }

    @Test
    fun `user handle keys coexist with the presenter provider`() = runTest(dispatcher) {
        val factory = viewModelFactory {
            initializer { KeepHandleViewModel(createSavedStateHandle()) }
        }
        val host = Host(null)
        val vm = host.create("vm", KeepHandleViewModel::class.java, factory)
        vm.handle["user"] = 42
        vm.state
        advanceUntilIdle()
        vm.onEvent(7)
        advanceUntilIdle()

        val saved = host.save().parcelled()
        host.viewModelStore.clear()

        val next = Host(saved).create("vm", KeepHandleViewModel::class.java, factory)

        assertThat(next.handle.get<Int>("user")).isEqualTo(42)
        assertThat(next.state.value).isEqualTo(7)
    }

    @Test
    fun `two ViewModels with their own handles stay isolated`() = runTest(dispatcher) {
        val host = Host(null)
        val a = host.create("a", SaveableViewModel::class.java, saveableFactory)
        val b = host.create("b", SaveableViewModel::class.java, saveableFactory)
        a.state
        b.state
        advanceUntilIdle()
        a.onEvent(7)
        b.onEvent(9)
        advanceUntilIdle()

        val saved = host.save().parcelled()
        host.viewModelStore.clear()

        val next = Host(saved)
        val restoredA = next.create("a", SaveableViewModel::class.java, saveableFactory)
        val restoredB = next.create("b", SaveableViewModel::class.java, saveableFactory)

        assertThat(restoredA.state.value).isEqualTo(7)
        assertThat(restoredB.state.value).isEqualTo(9)
    }
}
