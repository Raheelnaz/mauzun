package io.github.raheelnaz.molecule.test

import io.github.raheelnaz.molecule.MoleculeViewModel
import io.github.raheelnaz.molecule.PresenterBinding
import io.github.raheelnaz.molecule.PresenterEntry

internal val <Event : Any, Model : Any, Effect : Any>
    MoleculeViewModel<Event, Model, Effect>.testBinding: PresenterBinding<Event, Model, Effect>
    get() = bindingInstance

internal fun <VM : MoleculeViewModel<*, *, *>> VM.testEntry(): PresenterEntry<VM> =
    PresenterEntry.create(this)
