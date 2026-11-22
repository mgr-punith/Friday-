package com.puneet.friday.ui

import kotlin.random.Random

/** Guard safe-area placement and long-help scrolling without an Android runtime. */
object PopupPlacementTests {
    fun run(): Int {
        var checks = 0
        fun expect(condition: Boolean, message: String) { check(condition) { message }; checks++ }
        val screen = PopupPlacement.Bounds(0, 24, 360, 800)
        fun place(anchor: PopupPlacement.Bounds, height: Int = 100) =
            PopupPlacement.calculate(screen, anchor, 280, height, 16, 4, 22)!!
        val above = place(PopupPlacement.Bounds(280, 350, 328, 398))
        expect(above.side == PopupPlacement.Side.ABOVE && above.top + above.height == 346,
            "Help fits above its trigger without covering it")
        expect(above.pointerX + above.left == 304, "The pointer aligns with the info icon's center")
        val below = place(PopupPlacement.Bounds(280, 40, 328, 88))
        expect(below.side == PopupPlacement.Side.BELOW && below.top == 92,
            "A top-edge icon gets a bubble below it")
        val bottom = place(PopupPlacement.Bounds(280, 720, 328, 768))
        expect(bottom.side == PopupPlacement.Side.ABOVE, "A bottom-edge icon gets a bubble above it")
        val left = place(PopupPlacement.Bounds(16, 350, 64, 398))
        expect(left.left == 16 && left.pointerX == 24, "A left-edge trigger keeps help on screen and its pointer aligned")
        val right = place(PopupPlacement.Bounds(310, 350, 358, 398))
        expect(right.left + right.width == 344 && right.pointerX <= right.width - 22,
            "A right-edge trigger keeps the pointer clear of the rounded corner")
        val long = place(PopupPlacement.Bounds(280, 150, 328, 198), 2_000)
        expect(long.side == PopupPlacement.Side.BELOW && long.top + long.height == 784,
            "Long help uses the larger available side and leaves the safe-area margin")
        val narrow = PopupPlacement.calculate(PopupPlacement.Bounds(10, 20, 180, 420),
            PopupPlacement.Bounds(100, 200, 148, 248), 280, 100, 16, 4, 22)!!
        expect(narrow.width == 138 && narrow.left == 26, "Narrow windows constrain the popup width")
        expect(PopupPlacement.calculate(PopupPlacement.Bounds(0, 0, 20, 20),
            PopupPlacement.Bounds(0, 0, 10, 10), 280, 100, 16, 4, 22) == null,
            "An unavailable visible area creates no popup")
        val random = Random(17)
        repeat(200) {
            val width = random.nextInt(140, 1200)
            val height = random.nextInt(260, 2000)
            val x = random.nextInt(0, width - 48)
            val y = random.nextInt(30, height - 48)
            val visible = PopupPlacement.Bounds(10, 30, 10 + width, 30 + height)
            val anchor = PopupPlacement.Bounds(10 + x, 30 + y, 58 + x, 78 + y)
            val result = PopupPlacement.calculate(visible, anchor, 280, random.nextInt(40, 1500), 16, 4, 22)!!
            expect(result.left >= visible.left + 16 && result.top >= visible.top + 16 &&
                result.left + result.width <= visible.right - 16 && result.top + result.height <= visible.bottom - 16,
                "Popup remains inside an offset visible window")
            expect(if (result.side == PopupPlacement.Side.ABOVE) result.top + result.height <= anchor.top - 4
                else result.top >= anchor.bottom + 4, "Popup never covers its trigger")
        }
        return checks
    }
}
