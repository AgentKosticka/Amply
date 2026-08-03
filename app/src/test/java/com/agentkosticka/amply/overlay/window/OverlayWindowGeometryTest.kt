package com.agentkosticka.amply.overlay.window

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayWindowGeometryTest {
    @Test
    fun `nothing rotation 90 subtracts left cutout and both margins`() {
        assertEquals(
            2542,
            OverlayWindowGeometry.landscapeWidthPx(
                displayWidthPx = 2800,
                edgeMarginPx = 48,
                occlusions = HorizontalOcclusionInsets(left = 162)
            )
        )
    }

    @Test
    fun `rotation 270 subtracts an equivalent right inset`() {
        assertEquals(
            2542,
            OverlayWindowGeometry.landscapeWidthPx(
                displayWidthPx = 2800,
                edgeMarginPx = 48,
                occlusions = HorizontalOcclusionInsets(right = 162)
            )
        )
    }

    @Test
    fun `asymmetric system bars and cutouts are both reserved`() {
        assertEquals(
            2190,
            OverlayWindowGeometry.landscapeWidthPx(
                displayWidthPx = 2400,
                edgeMarginPx = 40,
                occlusions = HorizontalOcclusionInsets(left = 90, right = 40)
            )
        )
    }

    @Test
    fun `ordinary landscape display retains symmetric margins`() {
        assertEquals(
            2320,
            OverlayWindowGeometry.landscapeWidthPx(
                displayWidthPx = 2400,
                edgeMarginPx = 40,
                occlusions = HorizontalOcclusionInsets()
            )
        )
    }

    @Test
    fun `very narrow display never requests more width than is available`() {
        assertEquals(
            380,
            OverlayWindowGeometry.landscapeWidthPx(
                displayWidthPx = 700,
                edgeMarginPx = 60,
                occlusions = HorizontalOcclusionInsets(left = 100, right = 100)
            )
        )
    }
}
