package io.github.raheelnaz.molecule

/** A screen-scoped presenter returned by [moleculeViewModel]. */
public class PresenterEntry<VM : MoleculeViewModel<*, *, *>> internal constructor(
    internal val viewModel: VM,
)

/** Returns the same UI-facing binding on every read. */
public val <
    Event : Any,
    Model : Any,
    Effect : Any,
    VM : MoleculeViewModel<Event, Model, Effect>,
> PresenterEntry<VM>.binding: PresenterBinding<Event, Model, Effect>
    get() = viewModel.bindingInstance
