package io.github.raheelnaz.mauzun

/** Marks APIs intended for DI adapter implementations. */
@RequiresOptIn(
    message = "This API is for Mauzun integration libraries.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class MauzunViewModelAdapterApi
