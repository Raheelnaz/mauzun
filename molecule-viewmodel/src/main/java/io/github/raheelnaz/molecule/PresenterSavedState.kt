package io.github.raheelnaz.molecule

import android.os.Bundle
import android.os.Parcelable
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.snapshots.SnapshotMutableState
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.lifecycle.SavedStateHandle
import java.io.Serializable

internal class PresenterSavedState(handle: SavedStateHandle?) {

    val registry: SaveableStateRegistry =
        SaveableStateRegistry(restoredValues(handle), ::canBeSaved)

    init {
        handle?.setSavedStateProvider(KEY) { savedBundle() }
    }

    fun performSave(): Map<String, List<Any?>> = registry.performSave()

    @Suppress("UNCHECKED_CAST")
    private fun savedBundle(): Bundle {
        val bundle = Bundle()
        for ((key, list) in registry.performSave()) {
            bundle.putParcelableArrayList(key, ArrayList(list) as ArrayList<Parcelable?>)
        }
        return bundle
    }

    internal companion object {
        internal const val KEY = "io.github.raheelnaz.molecule.presenter-saved-state"

        private fun canBeSaved(value: Any): Boolean {
            if (value is SnapshotMutableState<*>) {
                val policy = value.policy
                if (policy !== neverEqualPolicy<Any?>() &&
                    policy !== structuralEqualityPolicy<Any?>() &&
                    policy !== referentialEqualityPolicy<Any?>()
                ) {
                    return false
                }
                val stateValue = value.value ?: return true
                return canBeSaved(stateValue)
            }
            return value is Serializable || value is Parcelable
        }

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        private fun restoredValues(handle: SavedStateHandle?): Map<String, List<Any?>>? =
            when (val value = handle?.get<Any?>(KEY)) {
                null -> null
                is Bundle -> value.keySet().associateWith { key ->
                    value.getParcelableArrayList<Parcelable?>(key) as List<Any?>
                }
                else -> asRestoredValues(value)
            }

        private fun asRestoredValues(value: Any): Map<String, List<Any?>> {
            require(value is Map<*, *>) { "Restored presenter state was not a Map" }
            require(value.keys.all { it is String } && value.values.all { it is List<*> }) {
                "Restored presenter state had an unexpected shape"
            }
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, List<Any?>>
        }
    }
}
