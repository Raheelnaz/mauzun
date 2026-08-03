package io.github.raheelnaz.molecule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.currentComposer

@Composable
@OptIn(InternalComposeApi::class)
internal fun <T> withCompositionLocal(
    value: ProvidedValue<*>,
    content: @Composable () -> T,
): T {
    val composer = currentComposer
    composer.startProviders(arrayOf(value))
    val result = content()
    composer.endProviders()
    return result
}
