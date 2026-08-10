package io.github.raheelnaz.molecule

import android.os.Binder
import android.os.Bundle
import android.os.Parcelable
import android.util.Size
import android.util.SizeF
import android.util.SparseArray
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.snapshots.SnapshotMutableState
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.lifecycle.SavedStateHandle
import java.io.Serializable

internal class PresenterSavedState(handle: SavedStateHandle) {

    val registry: SaveableStateRegistry =
        SaveableStateRegistry(restoredValues(handle), ::canBeSaved)

    init {
        handle.setSavedStateProvider(KEY, ::savedBundle)
    }

    @Suppress("UNCHECKED_CAST")
    private fun savedBundle(): Bundle = Bundle().apply {
        for ((key, values) in registry.performSave()) {
            putParcelableArrayList(key, ArrayList(values) as ArrayList<Parcelable?>)
        }
    }

    companion object {
        internal const val KEY = "io.github.raheelnaz.molecule.presenter-saved-state.v1"

        private val acceptableClasses = arrayOf(
            Serializable::class.java,
            Parcelable::class.java,
            String::class.java,
            SparseArray::class.java,
            Binder::class.java,
            Size::class.java,
            SizeF::class.java,
        )

        private fun canBeSaved(value: Any): Boolean {
            if (value is SnapshotMutableState<*>) {
                val policy = value.policy
                if (policy !== neverEqualPolicy<Any?>() &&
                    policy !== structuralEqualityPolicy<Any?>() &&
                    policy !== referentialEqualityPolicy<Any?>()
                ) {
                    return false
                }
                return value.value?.let(::canBeSaved) ?: true
            }
            if (value is Function<*> && value is Serializable) return false
            return acceptableClasses.any { it.isInstance(value) }
        }

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        private fun restoredValues(handle: SavedStateHandle): Map<String, List<Any?>>? {
            val bundle = handle.get<Any?>(KEY) as? Bundle ?: return null
            return runCatching {
                bundle.keySet().associateWith { key ->
                    bundle.getParcelableArrayList<Parcelable?>(key) as List<Any?>
                }
            }.getOrNull()
        }
    }
}
