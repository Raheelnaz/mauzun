package io.github.raheelnaz.mauzun.test

import io.github.raheelnaz.mauzun.MauzunViewModel
import io.github.raheelnaz.mauzun.PresenterBinding
import io.github.raheelnaz.mauzun.PresenterEntry

internal val <Event : Any, Model : Any, Effect : Any>
    MauzunViewModel<Event, Model, Effect>.testBinding: PresenterBinding<Event, Model, Effect>
    get() = bindingInstance

internal fun <VM : MauzunViewModel<*, *, *>> VM.testEntry(): PresenterEntry<VM> =
    PresenterEntry.create(this)
