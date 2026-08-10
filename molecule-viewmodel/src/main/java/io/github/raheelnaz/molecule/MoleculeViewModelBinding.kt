package io.github.raheelnaz.molecule

/**
 * The UI-facing binding for this ViewModel. Every read returns the same instance;
 * [PresenterHost] restarts effect collection when the binding changes. The first read of the
 * binding's `state` starts the presenter, so do not read it from a constructor or `init` block.
 */
public val <Event : Any, Model : Any, Effect : Any>
    MoleculeViewModel<Event, Model, Effect>.presenterBinding: PresenterBinding<Event, Model, Effect>
    get() = bindingInstance
