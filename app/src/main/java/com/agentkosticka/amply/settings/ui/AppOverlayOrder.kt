package com.agentkosticka.amply.settings.ui

import com.agentkosticka.amply.settings.model.AppIdentity

internal fun moveAppIdentity(
    order: List<AppIdentity>,
    fromIndex: Int,
    toIndex: Int
): List<AppIdentity> {
    if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) {
        return order
    }
    return order.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

internal fun clampDraggedAppTop(
    rawTop: Float,
    itemSize: Int,
    viewportStart: Int,
    viewportEnd: Int
): Float {
    val minimum = viewportStart.toFloat()
    val maximum = (viewportEnd - itemSize).coerceAtLeast(viewportStart).toFloat()
    return rawTop.coerceIn(minimum, maximum)
}

internal fun draggedTranslationForSlot(visualTop: Float, slotOffset: Int): Float =
    visualTop - slotOffset

internal fun adjacentReorderTarget(
    currentIndex: Int,
    lastIndex: Int,
    draggedCenter: Float,
    previousCenter: Float?,
    nextCenter: Float?
): Int = when {
    currentIndex > 0 && previousCenter != null && draggedCenter <= previousCenter ->
        currentIndex - 1
    currentIndex < lastIndex && nextCenter != null && draggedCenter >= nextCenter ->
        currentIndex + 1
    else -> currentIndex
}

/**
 * Caps edge auto-scroll only after the lazy layout has caught up with the latest logical reorder.
 * A logical order update and its LazyColumn placement happen on different frames; treating stale
 * placement data as current would stop a drag before the first/last card is actually visible.
 */
internal fun capReorderEdgeScrollDelta(
    requestedDelta: Float,
    draggedOrderIndex: Int,
    lastOrderIndex: Int,
    renderedLazyIndex: Int?,
    expectedLazyIndex: Int?,
    itemOffset: Int?,
    itemSize: Int,
    viewportStart: Int,
    viewportEnd: Int
): Float {
    if (requestedDelta == 0f || itemOffset == null) return requestedDelta

    val renderedOrderIsCurrent =
        expectedLazyIndex == null || renderedLazyIndex == expectedLazyIndex
    if (!renderedOrderIsCurrent) return requestedDelta

    if (requestedDelta < 0f && draggedOrderIndex <= 0) {
        val remaining = (viewportStart - itemOffset).coerceAtLeast(0)
        if (remaining == 0) return 0f
        return requestedDelta.coerceAtLeast(-remaining.toFloat())
    }
    if (requestedDelta > 0f && draggedOrderIndex >= lastOrderIndex) {
        val remaining = (itemOffset + itemSize - viewportEnd).coerceAtLeast(0)
        if (remaining == 0) return 0f
        return requestedDelta.coerceAtMost(remaining.toFloat())
    }
    return requestedDelta
}

/**
 * Reorders only the apps currently shown by the Apps tab while retaining the relative slots of
 * apps hidden by search/list filters. This prevents a filtered drag from silently dropping or
 * scrambling saved overlay entries.
 */
internal fun mergeVisibleAppOrder(
    existingOrder: List<AppIdentity>,
    allKnownApps: List<AppIdentity>,
    reorderedVisibleApps: List<AppIdentity>
): List<AppIdentity> {
    val reorderedVisible = reorderedVisibleApps.distinct()
    val visibleSet = reorderedVisible.toSet()
    val base = (existingOrder + allKnownApps + reorderedVisible).distinct()
    val replacements = reorderedVisible.iterator()

    return base.map { identity ->
        if (identity in visibleSet && replacements.hasNext()) replacements.next() else identity
    }
}
