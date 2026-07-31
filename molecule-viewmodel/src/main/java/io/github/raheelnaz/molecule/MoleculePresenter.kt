package io.github.raheelnaz.molecule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** State, effects, and events exposed to UI code. */
public interface MoleculePresenter<Event : Any, Model : Any, Effect : Any> {

    public val state: StateFlow<Model>

    /** One-off effects. Concurrent collectors split this stream. */
    public val effects: Flow<Effect>

    /** Safe to call from any thread. */
    public fun onEvent(event: Event)
}
