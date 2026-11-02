package com.deepseekharness.app.pet

import kotlin.math.abs
import kotlin.math.roundToInt

/** The screen and what to keep clear of it (status / navigation bars, the camera cutout), in px. */
data class SafeArea(
    val screenW: Int,
    val screenH: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

enum class Side { LEFT, RIGHT }

/** Where the pet rests: an edge, and how far down the usable height (0 top … 1 bottom). */
data class Dock(val side: Side, val yFraction: Float) {
    companion object {
        /** First run: right edge, a little below the middle, clear of the app's header controls. */
        val DEFAULT = Dock(Side.RIGHT, 0.62f)
    }
}

data class WindowPos(val x: Int, val y: Int)

/** A rectangle on screen, in px. */
data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun intersects(o: ScreenRect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** Which way a bubble's tail points (towards the pet): [DOWN] when the bubble is above it. */
enum class Tail { DOWN, UP, LEFT, RIGHT }

/**
 * A bubble's window: where it goes, its size with the tail, where the tail sits along its edge
 * (x for [Tail.DOWN] / [Tail.UP], y for [Tail.LEFT] / [Tail.RIGHT]), and which way it points.
 */
data class BubblePlacement(val pos: WindowPos, val w: Int, val h: Int, val tailAt: Int, val tail: Tail) {
    val rect: ScreenRect get() = ScreenRect(pos.x, pos.y, pos.x + w, pos.y + h)
}

/**
 * Dragging and the snap to the nearest side. The dock is kept as a side plus a fraction of the
 * usable height, so it survives rotation and a different screen size.
 */
object EdgeSnap {
    /** A release faster than this (px/s) throws the pet to the side it was thrown towards. */
    const val FLING_PX_PER_S = 1200f

    fun minX(area: SafeArea, margin: Int) = area.left + margin
    fun maxX(area: SafeArea, w: Int, margin: Int) = maxOf(minX(area, margin), area.screenW - area.right - margin - w)
    fun minY(area: SafeArea, margin: Int) = area.top + margin
    fun maxY(area: SafeArea, h: Int, margin: Int) = maxOf(minY(area, margin), area.screenH - area.bottom - margin - h)

    /** The side a window at [x] (width [w]) released with [velocityX] settles on. */
    fun sideFor(x: Int, w: Int, area: SafeArea, velocityX: Float): Side {
        if (abs(velocityX) >= FLING_PX_PER_S) return if (velocityX < 0) Side.LEFT else Side.RIGHT
        val center = x + w / 2f
        return if (center < area.screenW / 2f) Side.LEFT else Side.RIGHT
    }

    /** The dock for a window released at ([x], [y]). */
    fun dockFor(x: Int, y: Int, w: Int, h: Int, area: SafeArea, margin: Int, velocityX: Float = 0f): Dock {
        val side = sideFor(x, w, area, velocityX)
        val lo = minY(area, margin)
        val hi = maxY(area, h, margin)
        val f = if (hi <= lo) 0f else ((y - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
        return Dock(side, f)
    }

    /** Window position for [dock]. */
    fun position(dock: Dock, w: Int, h: Int, area: SafeArea, margin: Int): WindowPos {
        val x = if (dock.side == Side.LEFT) minX(area, margin) else maxX(area, w, margin)
        val lo = minY(area, margin)
        val hi = maxY(area, h, margin)
        val y = lo + ((hi - lo) * dock.yFraction.coerceIn(0f, 1f)).roundToInt()
        return WindowPos(x, y)
    }

    /** While dragging: follow the finger, but keep the whole pet on screen. */
    fun clampDrag(x: Int, y: Int, w: Int, h: Int, area: SafeArea): WindowPos = WindowPos(
        x.coerceIn(0, maxOf(0, area.screenW - w)),
        y.coerceIn(area.top, maxOf(area.top, area.screenH - area.bottom - h)),
    )

    /**
     * Where a bubble goes, as the chat page places it (docs/design/pets.md §2.7): above the picture
     * on screen now ([art]: the body with the current animation's cell, so over a jump, a thought
     * cloud or a raised 「!」, never in front of them), [gap] clear of its top, its inner edge lined
     * up with the body's ([body]) so it reaches towards the middle of the screen, the tail at the
     * body's middle. No room above (docked at the top): beside the picture on the side towards the
     * middle, level with the body; no room there either: below the picture. Always inside the
     * safe area. [bw]×[bh] is the card; the window adds [tailH] on the tail's side. The tail keeps
     * [tailInset] clear of the corners (less on a short side).
     */
    fun bubble(
        body: ScreenRect, art: ScreenRect, side: Side, bw: Int, bh: Int, tailH: Int,
        area: SafeArea, gap: Int, margin: Int, tailInset: Int,
    ): BubblePlacement {
        val left = area.left + margin
        val right = area.screenW - area.right - margin
        val top = area.top + margin
        val bottom = area.screenH - area.bottom - margin
        fun inner(w: Int) = (if (side == Side.RIGHT) body.right - w else body.left).coerceIn(left, maxOf(left, right - w))
        fun along(len: Int, at: Int, inset: Int) = at.coerceIn(inset, maxOf(inset, len - inset))
        val tall = bh + tailH
        val above = art.top - gap - tall
        if (above >= top) {
            val x = inner(bw)
            return BubblePlacement(WindowPos(x, above), bw, tall, along(bw, body.centerX - x, tailInset), Tail.DOWN)
        }
        val wide = bw + tailH
        val besideX = if (side == Side.RIGHT) art.left - gap - wide else art.right + gap
        if (besideX >= left && besideX + wide <= right) {
            val y = (body.centerY - bh / 2).coerceIn(top, maxOf(top, bottom - bh))
            val tail = along(bh, body.centerY - y, minOf(tailInset, bh / 2))
            return BubblePlacement(WindowPos(besideX, y), wide, bh, tail, if (side == Side.RIGHT) Tail.RIGHT else Tail.LEFT)
        }
        val x = inner(bw)
        val y = (art.bottom + gap).coerceIn(top, maxOf(top, bottom - tall))
        return BubblePlacement(WindowPos(x, y), bw, tall, along(bw, body.centerX - x, tailInset), Tail.UP)
    }

    /** Centre of the 「×」 target shown at the bottom middle while dragging. */
    fun dismissCenter(area: SafeArea, size: Int, gap: Int): WindowPos =
        WindowPos(area.left + (area.screenW - area.left - area.right) / 2, area.screenH - area.bottom - gap - size / 2)

    /** Whether a window at [pos] (size [w]×[h]) is over the target: its centre within [radius] of the target's. */
    fun overDismiss(pos: WindowPos, w: Int, h: Int, target: WindowPos, radius: Int): Boolean {
        val dx = (pos.x + w / 2 - target.x).toLong()
        val dy = (pos.y + h / 2 - target.y).toLong()
        return dx * dx + dy * dy <= radius.toLong() * radius
    }
}
