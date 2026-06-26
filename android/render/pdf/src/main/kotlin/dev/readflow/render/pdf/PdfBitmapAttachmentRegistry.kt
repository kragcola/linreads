package dev.readflow.render.pdf

import java.util.Collections
import java.util.WeakHashMap

internal class PdfBitmapAttachmentRegistry<T : Any, O : Any>(
    private val attachedValue: (O) -> T?,
    private val clearAttachment: (O) -> Unit,
) {
    private val owners = Collections.newSetFromMap(WeakHashMap<O, Boolean>())

    fun track(owner: O) {
        owners.add(owner)
    }

    fun untrack(owner: O) {
        owners.remove(owner)
    }

    fun release(value: T, releaseValue: (T) -> Unit) {
        owners.toList().forEach { owner ->
            if (attachedValue(owner) === value) {
                clearAttachment(owner)
            }
        }
        releaseValue(value)
    }
}
