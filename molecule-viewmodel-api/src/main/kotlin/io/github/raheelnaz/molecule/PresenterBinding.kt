package io.github.raheelnaz.molecule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What a screen consumes: models out, effects out, events in. Obtained via `presenterBinding`. */
public interface PresenterBinding<in Event : Any, out Model : Any, out Effect : Any> {

    /** The latest model to render. */
    public val state: StateFlow<Model>

    /** One-off work for the UI. Each effect is delivered to one collector. */
    public val effects: Flow<Effect>

    /** Sends an event to the presenter. Safe to call from any thread. */
    public fun onEvent(event: Event)
}
