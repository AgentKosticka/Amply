package com.agentkosticka.amply.settings.ui

import com.agentkosticka.amply.settings.model.AppIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class AppOverlayOrderTest {
    private fun app(name: String) = AppIdentity(userId = 0, packageName = "example.$name")

    @Test fun moveChangesOnlyTheRequestedPosition() {
        val first = app("first")
        val second = app("second")
        val third = app("third")

        assertEquals(
            listOf(second, third, first),
            moveAppIdentity(listOf(first, second, third), fromIndex = 0, toIndex = 2)
        )
    }

    @Test fun filteredReorderPreservesHiddenAppSlots() {
        val first = app("first")
        val hidden = app("hidden")
        val second = app("second")
        val final = app("final")

        assertEquals(
            listOf(second, hidden, first, final),
            mergeVisibleAppOrder(
                existingOrder = listOf(first, hidden, second, final),
                allKnownApps = listOf(first, hidden, second, final),
                reorderedVisibleApps = listOf(second, first)
            )
        )
    }

    @Test fun previouslyUnorderedAppsAreAddedWithoutDuplicates() {
        val first = app("first")
        val second = app("second")

        assertEquals(
            listOf(second, first),
            mergeVisibleAppOrder(
                existingOrder = emptyList(),
                allKnownApps = listOf(first, second),
                reorderedVisibleApps = listOf(second, first)
            )
        )
    }

    @Test fun draggedCardStaysFullyInsideTheViewport() {
        assertEquals(100f, clampDraggedAppTop(-200f, 300, 100, 1_000))
        assertEquals(700f, clampDraggedAppTop(950f, 300, 100, 1_000))
        assertEquals(420f, clampDraggedAppTop(420f, 300, 100, 1_000))
    }

    @Test fun slotSwapPreservesTheDraggedCardsScreenPosition() {
        val visualTop = 480f
        val oldSlot = 260
        val newSlot = 520

        assertEquals(
            oldSlot + draggedTranslationForSlot(visualTop, oldSlot),
            newSlot + draggedTranslationForSlot(visualTop, newSlot)
        )
    }
}
