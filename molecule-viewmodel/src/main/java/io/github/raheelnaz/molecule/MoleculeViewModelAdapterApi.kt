package io.github.raheelnaz.molecule

/** Marks APIs intended for DI adapter implementations. */
@RequiresOptIn(
    message = "This API is for Molecule ViewModel integration libraries.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class MoleculeViewModelAdapterApi
