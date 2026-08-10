package io.github.raheelnaz.molecule

import kotlinx.coroutines.flow.Flow

/** Marks APIs intended for testing libraries. */
@RequiresOptIn(
    message = "This API is for Molecule ViewModel testing libraries.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class MoleculeViewModelTestingApi

/** Returns the production effect stream without exposing the ViewModel's produced state. */
@JvmSynthetic
@MoleculeViewModelTestingApi
public fun <Event : Any, Model : Any, Effect : Any>
    MoleculeViewModel<Event, Model, Effect>.effectsForTest(): Flow<Effect> = bindingInstance.effects
