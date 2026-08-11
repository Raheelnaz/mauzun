package io.github.raheelnaz.mauzun

import kotlinx.coroutines.flow.Flow

/** Marks APIs intended for testing libraries. */
@RequiresOptIn(
    message = "This API is for Mauzun testing libraries.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class MauzunViewModelTestingApi

/** Returns the production effect stream without exposing the ViewModel's produced state. */
@JvmSynthetic
@MauzunViewModelTestingApi
public fun <Event : Any, Model : Any, Effect : Any>
    MauzunViewModel<Event, Model, Effect>.effectsForTest(): Flow<Effect> = bindingInstance.effects
