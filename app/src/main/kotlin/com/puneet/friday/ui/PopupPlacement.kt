package com.puneet.friday.ui

/** Screen-pixel geometry, independent of Android so edge cases can run in CI. */
object PopupPlacement {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }
    enum class Side { ABOVE, BELOW }
    data class Placement(val left: Int, val top: Int, val width: Int, val height: Int,
        val pointerX: Int, val side: Side)

    fun calculate(
        visible: Bounds,
        anchor: Bounds,
        requestedWidth: Int,
        requestedHeight: Int,
        margin: Int,
        gap: Int,
        pointerInset: Int,
    ): Placement? {
        val frame = Bounds(visible.left + margin, visible.top + margin, visible.right - margin, visible.bottom - margin)
        if (frame.width <= 0 || frame.height <= 0 || requestedWidth <= 0 || requestedHeight <= 0) return null
        val width = minOf(requestedWidth, frame.width)
        val above = (minOf(anchor.top - gap, frame.bottom) - frame.top).coerceAtLeast(0)
        val below = (frame.bottom - maxOf(anchor.bottom + gap, frame.top)).coerceAtLeast(0)
        val side = when {
            requestedHeight <= above -> Side.ABOVE
            requestedHeight <= below -> Side.BELOW
            above >= below -> Side.ABOVE
            else -> Side.BELOW
        }
        val height = minOf(requestedHeight, if (side == Side.ABOVE) above else below)
        if (height <= 0) return null
        val left = (anchor.right - width).coerceIn(frame.left, frame.right - width)
        val top = if (side == Side.ABOVE) minOf(anchor.top - gap, frame.bottom) - height
            else maxOf(anchor.bottom + gap, frame.top)
        val inset = pointerInset.coerceIn(0, width / 2)
        val pointerX = ((anchor.left + anchor.right) / 2 - left).coerceIn(inset, width - inset)
        return Placement(left, top, width, height, pointerX, side)
    }
}
