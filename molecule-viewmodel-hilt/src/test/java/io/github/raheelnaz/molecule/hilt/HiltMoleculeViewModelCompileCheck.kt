package io.github.raheelnaz.molecule.hilt

import androidx.compose.runtime.Composable
import assertk.assertFailure
import assertk.assertions.isInstanceOf
import io.github.raheelnaz.molecule.MoleculeViewModel
import kotlinx.coroutines.flow.Flow
import org.junit.Test

// Compiling this file is the test. The adapters are inline and reified, so their shapes never
// appear in the api dump; these call sites break the build if either signature drifts.

private class CompileCheckViewModel : MoleculeViewModel<Int, Int, Nothing>() {
    @Composable
    override fun present(events: Flow<Int>): Int = 0
}

private class CompileCheckFactory

@Composable
@Suppress("unused")
private fun plainShape(): CompileCheckViewModel = hiltMoleculeViewModel(key = "compile-check")

@Composable
@Suppress("unused")
private fun assistedShape(): CompileCheckViewModel =
    hiltMoleculeViewModel<CompileCheckViewModel, CompileCheckFactory>(
        creationCallback = { _ -> error("compile only") },
    )

class HiltMoleculeViewModelTest {

    @Test
    fun `the reserved prefix is rejected before hilt runs`() {
        assertFailure {
            requireUsableKey("io.github.raheelnaz.molecule.presenter-saved-state-holder:screen")
        }.isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `ordinary keys pass`() {
        requireUsableKey(null)
        requireUsableKey("screen")
    }
}
