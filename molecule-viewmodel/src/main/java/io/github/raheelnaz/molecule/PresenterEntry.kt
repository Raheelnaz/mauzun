package io.github.raheelnaz.molecule

/** A presenter returned by [moleculeViewModel], scoped to the owner it was retrieved from. */
public class PresenterEntry<VM : MoleculeViewModel<*, *, *>> private constructor(
    @get:JvmSynthetic
    internal val viewModel: VM,
) {
    internal companion object {
        // An internal constructor is a public constructor on the JVM.
        @JvmSynthetic
        internal fun <VM : MoleculeViewModel<*, *, *>> create(viewModel: VM): PresenterEntry<VM> =
            PresenterEntry(viewModel)
    }
}

/** Returns the same UI-facing binding on every read. */
public val <
    Event : Any,
    Model : Any,
    Effect : Any,
    VM : MoleculeViewModel<Event, Model, Effect>,
> PresenterEntry<VM>.binding: PresenterBinding<Event, Model, Effect>
    get() = viewModel.bindingInstance
