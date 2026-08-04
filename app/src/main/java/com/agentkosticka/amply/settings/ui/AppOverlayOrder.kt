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
