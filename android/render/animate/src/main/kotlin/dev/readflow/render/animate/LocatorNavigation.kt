package dev.readflow.render.animate

import dev.readflow.core.model.Locator
import dev.readflow.core.model.LocatorStrategy

internal fun moveLocatorBy(locator: Locator, totalItems: Int, delta: Int): Locator? {
    if (totalItems <= 0 || delta == 0) return null
    val lastIndex = totalItems - 1
    return when (val strategy = locator.strategy) {
        is LocatorStrategy.Page -> {
            val index = (strategy.index + delta).coerceIn(0, lastIndex)
            if (index == strategy.index) null else locator.withIndex(index, totalItems)
        }
        is LocatorStrategy.Section -> {
            val index = (strategy.elementIndex + delta).coerceIn(0, lastIndex)
            if (index == strategy.elementIndex) null else locator.withIndex(index, totalItems)
        }
        is LocatorStrategy.ByteOffset,
        LocatorStrategy.Unknown,
        -> null
    }
}

internal fun pageIndexFromLocator(locator: Locator, totalItems: Int): Int {
    val total = totalItems.coerceAtLeast(1)
    val lastIndex = total - 1
    val index = when (val strategy = locator.strategy) {
        is LocatorStrategy.Page -> strategy.index
        is LocatorStrategy.Section -> strategy.elementIndex
        is LocatorStrategy.ByteOffset,
        LocatorStrategy.Unknown,
        -> locator.totalProgression?.let { (it * total).toInt() } ?: 0
    }
    return index.coerceIn(0, lastIndex)
}

private fun Locator.withIndex(index: Int, totalItems: Int): Locator {
    val total = totalItems.coerceAtLeast(1)
    val progression = index.toFloat() / total
    val strategy = when (val current = strategy) {
        is LocatorStrategy.Page -> current.copy(index = index, total = total)
        is LocatorStrategy.Section -> current.copy(elementIndex = index, charOffset = 0)
        else -> current
    }
    return copy(
        strategy = strategy,
        progression = progression,
        totalProgression = progression,
    )
}
