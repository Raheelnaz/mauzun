package io.github.raheelnaz.molecule

/**
 * Returns the UI-facing binding for this ViewModel. Every call returns the same instance;
 * [PresenterHost] restarts effect collection when the binding changes. The first read of the
 * binding's `state` starts the presenter, so do not read it from a constructor or `init` block.
 */
public fun <Event : Any, Model : Any, Effect : Any> MoleculeViewModel<Event, Model, Effect>.binding(): PresenterBinding<Event, Model, Effect> =
    bindingInstance
