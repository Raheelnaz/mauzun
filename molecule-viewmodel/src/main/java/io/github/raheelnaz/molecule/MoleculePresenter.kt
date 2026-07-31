package io.github.raheelnaz.molecule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The contract a screen consumes: state, one-off effects, and an event sink. [MoleculeViewModel]
 * implements this. Depend on the interface in UI code.
 */
public interface MoleculePresenter<Event : Any, Model : Any, Effect : Any> {

    public val state: StateFlow<Model>

    /**
     * One-off effects such as navigation. Collect from exactly one place at a time. Concurrent
     * collectors split the stream between them. [UiFactory] is the intended collector.
     */
    public val effects: Flow<Effect>

    /** Safe to call from any thread. */
    public fun onEvent(event: Event)
}
