package io.github.raheelnaz.molecule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The model, events, and effects used by a screen. */
public interface MoleculePresenter<Event : Any, Model : Any, Effect : Any> {

    /** The latest model to render. */
    public val state: StateFlow<Model>

    /** One-off work for the UI. Each effect is delivered to one collector. */
    public val effects: Flow<Effect>

    /** Sends an event to the presenter. Safe to call from any thread. */
    public fun onEvent(event: Event)
}
