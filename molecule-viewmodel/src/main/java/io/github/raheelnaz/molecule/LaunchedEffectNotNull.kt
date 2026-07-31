package io.github.raheelnaz.molecule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineScope

/**
 * [LaunchedEffect] that runs [block] only when [a] is non-null, passing the value already
 * checked. Any change restarts the effect. A value turning null restarts it into a no-op,
 * cancelling in-flight work.
 */
@Composable
public fun <A : Any> LaunchedEffectNotNull(
    a: A?,
    block: suspend CoroutineScope.(A) -> Unit,
) {
    LaunchedEffect(a) { if (a != null) block(a) }
}

/** Two-value [LaunchedEffectNotNull]: [block] runs only when both values are non-null. */
@Composable
public fun <A : Any, B : Any> LaunchedEffectNotNull(
    a: A?,
    b: B?,
    block: suspend CoroutineScope.(A, B) -> Unit,
) {
    LaunchedEffect(a, b) { if (a != null && b != null) block(a, b) }
}

/** Three-value [LaunchedEffectNotNull]: [block] runs only when all values are non-null. */
@Composable
public fun <A : Any, B : Any, C : Any> LaunchedEffectNotNull(
    a: A?,
    b: B?,
    c: C?,
    block: suspend CoroutineScope.(A, B, C) -> Unit,
) {
    LaunchedEffect(a, b, c) { if (a != null && b != null && c != null) block(a, b, c) }
}
