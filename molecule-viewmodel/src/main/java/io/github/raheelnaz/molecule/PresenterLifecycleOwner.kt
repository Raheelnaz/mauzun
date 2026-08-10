package io.github.raheelnaz.molecule

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

// createUnsafe skips main thread enforcement. Every mutation funnels through moveToState,
// which runs on the composition's thread: main in production, the test thread under runTest.
internal class PresenterLifecycleOwner(
    initialState: Lifecycle.State = Lifecycle.State.RESUMED,
) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
        .apply { currentState = initialState }

    override val lifecycle: Lifecycle get() = registry

    fun moveToState(next: Lifecycle.State) {
        registry.currentState = next
    }
}
