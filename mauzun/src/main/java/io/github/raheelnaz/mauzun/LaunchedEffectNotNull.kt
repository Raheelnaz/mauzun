package io.github.raheelnaz.mauzun

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CoroutineScope

/** Runs [block] when [a] is non-null. A change to [a], including null, cancels the old block. */
@Composable
public fun <A : Any> LaunchedEffectNotNull(
    a: A?,
    block: suspend CoroutineScope.(A) -> Unit,
) {
    LaunchedEffect(a) { if (a != null) block(a) }
}

/** Runs [block] when [a] and [b] are non-null. Changing either value cancels the old block. */
@Composable
public fun <A : Any, B : Any> LaunchedEffectNotNull(
    a: A?,
    b: B?,
    block: suspend CoroutineScope.(A, B) -> Unit,
) {
    LaunchedEffect(a, b) { if (a != null && b != null) block(a, b) }
}

/** Runs [block] when all values are non-null. Changing any value cancels the old block. */
@Composable
public fun <A : Any, B : Any, C : Any> LaunchedEffectNotNull(
    a: A?,
    b: B?,
    c: C?,
    block: suspend CoroutineScope.(A, B, C) -> Unit,
) {
    LaunchedEffect(a, b, c) { if (a != null && b != null && c != null) block(a, b, c) }
}
