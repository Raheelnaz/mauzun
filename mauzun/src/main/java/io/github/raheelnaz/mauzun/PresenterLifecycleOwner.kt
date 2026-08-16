package io.github.raheelnaz.mauzun

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.IdentityHashMap

internal class PresenterLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply {
        currentState = Lifecycle.State.CREATED
    }
    private val sources = IdentityHashMap<Lifecycle, Attachment>()
    private var cleared = false

    override val lifecycle: Lifecycle get() = registry

    fun attach(source: Lifecycle): () -> Unit {
        if (cleared || source.currentState == Lifecycle.State.DESTROYED) return {}

        val existing = sources[source]
        if (existing != null) {
            existing.references++
        } else {
            val observer = LifecycleEventObserver { _, _ ->
                if (source.currentState == Lifecycle.State.DESTROYED) {
                    remove(source)
                } else {
                    updateState()
                }
            }
            sources[source] = Attachment(references = 1, observer = observer)
            source.addObserver(observer)

            if (source.currentState == Lifecycle.State.DESTROYED) {
                remove(source)
            } else {
                updateState()
            }
        }

        var released = false
        return {
            if (!released) {
                released = true
                release(source)
            }
        }
    }

    fun clear() {
        if (cleared) return
        cleared = true
        for ((source, attachment) in sources) {
            source.removeObserver(attachment.observer)
        }
        sources.clear()
        registry.currentState = Lifecycle.State.DESTROYED
    }

    private fun release(source: Lifecycle) {
        val attachment = sources[source] ?: return
        attachment.references--
        if (attachment.references == 0) remove(source)
    }

    private fun remove(source: Lifecycle) {
        val attachment = sources.remove(source) ?: return
        source.removeObserver(attachment.observer)
        updateState()
    }

    private fun updateState() {
        if (cleared) return
        var next = Lifecycle.State.CREATED
        val iterator = sources.entries.iterator()
        while (iterator.hasNext()) {
            val (source, attachment) = iterator.next()
            val state = source.currentState
            if (state == Lifecycle.State.DESTROYED) {
                source.removeObserver(attachment.observer)
                iterator.remove()
            } else if (state > next) {
                next = state
            }
        }
        registry.currentState = next
    }

    private class Attachment(
        var references: Int,
        val observer: LifecycleEventObserver,
    )
}
