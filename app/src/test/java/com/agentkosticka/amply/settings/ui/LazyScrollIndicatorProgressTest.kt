package com.agentkosticka.amply.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyScrollIndicatorProgressTest {
    @Test
    fun visibleSliceReachesTrackEndsWithoutForcedEndpointState() {
        val top = fractions(
            firstIndex = 0,
            firstOffset = 0,
            firstSize = 100,
            lastIndex = 2,
            lastOffset = 200,
            lastSize = 100,
            viewportStart = 0,
            viewportEnd = 250
        )
        val bottom = fractions(
            firstIndex = 7,
            firstOffset = -50,
            firstSize = 100,
            lastIndex = 9,
            lastOffset = 150,
            lastSize = 100,
            viewportStart = 0,
            viewportEnd = 250
        )

        assertEquals(0f, top.start, 0f)
        assertEquals(1f, bottom.end, 0f)
        assertEquals(0f, stableLazyScrollThumbProgress(top), 0f)
        assertEquals(1f, stableLazyScrollThumbProgress(bottom), 0f)
    }

    @Test
    fun startIsContinuousWhenFirstVisibleItemChanges() {
        val justBeforeBoundary = fractions(
            firstIndex = 2,
            firstOffset = -99,
            firstSize = 100,
            lastIndex = 4,
            lastOffset = 101,
            lastSize = 100
        )
        val atBoundary = fractions(
            firstIndex = 3,
            firstOffset = 0,
            firstSize = 240,
            lastIndex = 4,
            lastOffset = 240,
            lastSize = 100
        )

        assertTrue(atBoundary.start >= justBeforeBoundary.start)
        assertTrue(atBoundary.start - justBeforeBoundary.start < 0.002f)
        assertTrue(
            stableLazyScrollThumbProgress(atBoundary) -
                stableLazyScrollThumbProgress(justBeforeBoundary) < 0.003f
        )
    }

    @Test
    fun startIsContinuousAcrossItemSpacing() {
        val justBeforeBoundary = fractions(
            firstIndex = 2,
            firstOffset = -113,
            firstSize = 100,
            lastIndex = 4,
            lastOffset = 115,
            lastSize = 100,
            spacing = 14
        )
        val atBoundary = fractions(
            firstIndex = 3,
            firstOffset = 0,
            firstSize = 240,
            lastIndex = 4,
            lastOffset = 254,
            lastSize = 100,
            spacing = 14
        )

        assertTrue(atBoundary.start >= justBeforeBoundary.start)
        assertTrue(atBoundary.start - justBeforeBoundary.start < 0.001f)
        assertTrue(
            stableLazyScrollThumbProgress(atBoundary) -
                stableLazyScrollThumbProgress(justBeforeBoundary) < 0.003f
        )
    }

    @Test
    fun endIsContinuousWhenNextItemEntersViewport() {
        val justBeforeBoundary = fractions(
            firstIndex = 0,
            firstOffset = -20,
            firstSize = 100,
            lastIndex = 2,
            lastOffset = 208,
            lastSize = 100,
            spacing = 14,
            viewportEnd = 321
        )
        val justAfterBoundary = fractions(
            firstIndex = 0,
            firstOffset = -21,
            firstSize = 100,
            lastIndex = 3,
            lastOffset = 321,
            lastSize = 240,
            spacing = 14,
            viewportEnd = 322
        )

        assertTrue(justAfterBoundary.end >= justBeforeBoundary.end)
        assertTrue(justAfterBoundary.end - justBeforeBoundary.end < 0.002f)
        assertTrue(
            kotlin.math.abs(
                stableLazyScrollThumbProgress(justAfterBoundary) -
                    stableLazyScrollThumbProgress(justBeforeBoundary)
            ) < 0.003f
        )
    }

    @Test
    fun finalItemApproachesBottomContinuously() {
        val onePixelBefore = fractions(
            firstIndex = 7,
            firstOffset = -49,
            firstSize = 100,
            lastIndex = 9,
            lastOffset = 150,
            lastSize = 100,
            spacing = 14,
            viewportEnd = 249
        )
        val atBottom = fractions(
            firstIndex = 7,
            firstOffset = -50,
            firstSize = 100,
            lastIndex = 9,
            lastOffset = 150,
            lastSize = 100,
            spacing = 14,
            viewportEnd = 250
        )

        assertEquals(1f, atBottom.end, 0f)
        assertTrue(atBottom.end - onePixelBefore.end < 0.002f)
    }

    @Test
    fun forwardScrollNeverMovesSliceBackwardAcrossDifferentItemSizes() {
        val samples = listOf(
            fractions(firstIndex = 1, firstOffset = -20, firstSize = 300).start,
            fractions(firstIndex = 1, firstOffset = -180, firstSize = 300).start,
            fractions(firstIndex = 2, firstOffset = 0, firstSize = 72).start,
            fractions(firstIndex = 2, firstOffset = -60, firstSize = 72).start,
            fractions(firstIndex = 3, firstOffset = 0, firstSize = 420).start
        )

        assertTrue(samples.zipWithNext().all { (before, after) -> after >= before })
    }

    private fun fractions(
        firstIndex: Int,
        firstOffset: Int,
        firstSize: Int,
        lastIndex: Int = firstIndex + 2,
        lastOffset: Int = 200,
        lastSize: Int = 100,
        spacing: Int = 0,
        viewportStart: Int = 0,
        viewportEnd: Int = 250,
        totalItems: Int = 10
    ): LazyScrollThumbFractions = stableLazyScrollThumbFractions(
        firstVisibleItemIndex = firstIndex,
        firstVisibleItemOffset = firstOffset,
        firstVisibleItemSize = firstSize,
        lastVisibleItemIndex = lastIndex,
        lastVisibleItemOffset = lastOffset,
        lastVisibleItemSize = lastSize,
        itemSpacing = spacing,
        viewportStartOffset = viewportStart,
        viewportEndOffset = viewportEnd,
        totalItemsCount = totalItems
    )
}
