package com.agentkosticka.amply.overlay.window

internal data class HorizontalOcclusionInsets(
    val left: Int = 0,
    val right: Int = 0
) {
    val total: Int get() = left.coerceAtLeast(0) + right.coerceAtLeast(0)
}

/** Pure window sizing shared by runtime code and OEM-geometry regression tests. */
internal object OverlayWindowGeometry {
    fun landscapeWidthPx(
        displayWidthPx: Int,
        edgeMarginPx: Int,
        occlusions: HorizontalOcclusionInsets
    ): Int = (displayWidthPx.coerceAtLeast(0) -
        edgeMarginPx.coerceAtLeast(0) * 2 -
        occlusions.total)
        .coerceAtLeast(1)
}

enum class OverlayAttachResult {
    ATTACHED,
    ALREADY_ATTACHED,
    FAILED
}
