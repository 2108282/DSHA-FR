package com.deepseekharness.app.phone.a11y

import com.deepseekharness.app.pet.Side
import com.deepseekharness.app.pet.WindowPos
import kotlin.math.ceil
import kotlin.math.roundToInt

// The control's pure half (measured in OverlayModelTest): what the line says, the steps behind it,
// the header's keys, the capsule's width, the ball's hold-to-stop and where the ball meets the
// capsule. ControlOverlay, OverlayCapsule and OverlayBall draw what these decide.

/**
 * A status line as the capsule shows it. The core says 「免确认 · DSH 正在操作 · 点「发送」」; the
 * capsule is DSH's own, so it shows 「点「发送」」 and keeps 免确认 for the card.
 */
internal data class StatusLine(
    /** What the capsule shows. */
    val text: String,
    /** The 免确认 prefix was there: nothing in this task asks. */
    val waived: Boolean,
    /** An action (tap, type, scroll, key) rather than reading the screen: it goes into the steps. */
    val acting: Boolean,
)

/**
 * Takes the prefixes off the core's lines. [waivedFormat] and [actingFormat] are the core's own
 * formats in the app language (`phone_waived_status`, `phone_acting`), so the prefixes match
 * whichever language the line came in.
 */
internal class StatusLines(waivedFormat: String, actingFormat: String) {
    private val waived = prefixOf(waivedFormat)
    private val acting = prefixOf(actingFormat)

    fun parse(raw: String): StatusLine {
        val whole = raw.trim()
        var t = whole
        var isWaived = false
        if (waived != null && t.startsWith(waived)) {
            isWaived = true
            t = t.substring(waived.length).trim()
        }
        var isActing = false
        if (acting != null && t.startsWith(acting)) {
            isActing = true
            t = t.substring(acting.length).trim()
        }
        return StatusLine(t.ifEmpty { whole }, isWaived, isActing)
    }

    companion object {
        /** What a one-argument format says before the argument (「免确认 · %1$s」 → 「免确认 · 」); null unless it ends in it. */
        fun prefixOf(format: String): String? {
            for (arg in ARGS) if (format.endsWith(arg)) return format.dropLast(arg.length).takeIf { it.isNotBlank() }
            return null
        }

        private val ARGS = listOf("%1\$s", "%s")
    }
}

/** The task's last actions, for the card: newest last, the same action twice in a row once. */
internal class StepHistory(private val capacity: Int = CAPACITY) {
    private val steps = ArrayDeque<String>()

    fun add(line: StatusLine) {
        if (!line.acting || steps.lastOrNull() == line.text) return
        steps.addLast(line.text)
        // One more than shown: the newest may be the line the header shows.
        while (steps.size > capacity + 1) steps.removeFirst()
    }

    /** Up to [limit] steps before [current] (the header's line), newest first. */
    fun before(current: StatusLine?, limit: Int): List<String> {
        val list = steps.toMutableList()
        if (current != null && current.acting && list.lastOrNull() == current.text) list.removeAt(list.lastIndex)
        return list.asReversed().take(limit.coerceAtLeast(0))
    }

    fun clear() = steps.clear()

    companion object {
        const val CAPACITY = 3
    }
}

/** The header's keys, left to right: the same in the capsule and the card, so 停止 never moves. */
internal enum class Slot { MARK, LINE, PAUSE, STOP }

/**
 * Sizes of the control, in dp. One radius for every form: the capsule's ends, the card's corners
 * and the ball are the same 24 dp curve, so a morph never re-bends a corner.
 */
internal object CapsuleMetrics {
    /** Capsule height, ball diameter, card header. */
    const val HEIGHT_DP = 48
    const val RADIUS_DP = 24

    /** Touch target of the mark, 暂停 and 停止 (≥ 48 dp). */
    const val KEY_DP = 48

    /** The visible disc of 暂停 / 停止: concentric with the capsule's round end (6 dp in all round). */
    const val DISC_DP = 36
    const val KEY_GAP_DP = 4

    /** 已解除限制's lock before the line, when any limit is lifted. */
    const val LOCK_DP = 14
    const val LOCK_GAP_DP = 6

    /** The line's chevron (opens the card) and the room after it. */
    const val CHEVRON_DP = 14
    const val CHEVRON_GAP_DP = 6
    const val LINE_END_DP = 6

    /** A stopped capsule (no keys) ends this far after its line. */
    const val STOPPED_END_DP = 20

    /** Width range; widths come in whole [STEP_DP] so a line a glyph longer doesn't nudge it. */
    const val MIN_DP = 120
    const val MAX_DP = 400
    const val STEP_DP = 8

    /** Gap to the screen sides, as the app's floating controls (page-design §7). */
    const val EDGE_DP = 12

    /** Below the status bar. */
    const val TOP_GAP_DP = 6

    fun slots(stopped: Boolean): List<Slot> =
        if (stopped) listOf(Slot.MARK, Slot.LINE) else listOf(Slot.MARK, Slot.LINE, Slot.PAUSE, Slot.STOP)

    /** The capsule's content width in dp for a line [textDp] wide ([chevron]: the line can open the card). */
    fun contentDp(textDp: Float, lock: Boolean, stopped: Boolean, chevron: Boolean = !stopped): Float {
        var w = KEY_DP + textDp
        if (lock) w += LOCK_DP + LOCK_GAP_DP
        if (chevron) w += CHEVRON_GAP_DP + CHEVRON_DP
        w += if (stopped) STOPPED_END_DP.toFloat() else (LINE_END_DP + KEY_DP + KEY_GAP_DP + KEY_DP).toFloat()
        return w
    }

    /** The widest the capsule (and the card) gets on a screen [screenW] px wide. */
    fun maxPx(screenW: Int, density: Float): Int =
        minOf(screenW - (2 * EDGE_DP * density).roundToInt(), (MAX_DP * density).roundToInt()).coerceAtLeast((MIN_DP * density).roundToInt())

    /** The capsule's width in px for content [contentPx] wide: whole steps, within the range. */
    fun widthPx(contentPx: Float, screenW: Int, density: Float): Int {
        val step = STEP_DP * density
        val stepped = (ceil(contentPx / step) * step).roundToInt()
        return stepped.coerceIn((MIN_DP * density).roundToInt(), maxPx(screenW, density))
    }

    /** How many past steps the card lists: one on a short screen (landscape), else three. */
    fun steps(screenHeightDp: Float): Int = if (screenHeightDp < 560f) 1 else StepHistory.CAPACITY
}

/**
 * The capsule's width over time. It grows at once (a longer line is never cut while there is
 * room), but shrinks only after the lines have been narrower for [shrinkAfterMs], and only by
 * more than [slack] px: the agent alternates 「正在看屏幕」 and 「点「…」」 every second, and a
 * capsule pulsing with them would be restless.
 */
internal class CapsuleWidth(private val slack: Int, private val shrinkAfterMs: Long = SHRINK_AFTER_MS) {
    var width = 0
        private set

    /** The widest line since they got narrower, and since when. */
    private var narrower = 0
    private var since: Long? = null

    /** When [offer] may shrink it; null while nothing is pending. */
    val shrinkAt: Long? get() = since?.let { it + shrinkAfterMs }

    /** A line wanting [wanted] px at [now]; true when [width] changed. */
    fun offer(wanted: Int, now: Long): Boolean {
        if (width == 0 || wanted >= width) {
            since = null
            if (wanted == width) return false
            width = wanted
            return true
        }
        if (width - wanted <= slack) {
            // Nearly as wide: keep it, and forget an older, narrower wish.
            since = null
            return false
        }
        val start = since
        if (start == null) {
            since = now
            narrower = wanted
            return false
        }
        narrower = maxOf(narrower, wanted)
        if (now - start < shrinkAfterMs) return false
        since = null
        width = narrower
        return true
    }

    /** At once, whatever it was (a state change, such as 已停止, rather than a new line). */
    fun force(wanted: Int): Boolean {
        since = null
        if (width == wanted) return false
        width = wanted
        return true
    }

    fun reset() {
        width = 0
        since = null
    }

    companion object {
        const val SHRINK_AFTER_MS = 1_200L
    }
}

/**
 * The ball's press-and-hold stop: a tap opens the capsule; holding still for [armMs] starts a ring
 * filling round the ball, and at [armMs] + [fillMs] the task stops. Letting go while it fills
 * cancels (it is neither a tap nor a stop); moving past the touch slop is a drag, never a stop.
 */
internal class HoldToStop(val armMs: Long = ARM_MS, val fillMs: Long = FILL_MS) {
    enum class State { IDLE, PRESSED, FILLING, FIRED, DRAGGING }

    /** [STOP]: stop now (the hold closed on this release); [STOPPED]: it already stopped while held. */
    enum class Release { TAP, CANCELLED, DRAG, STOP, STOPPED, NONE }

    var state = State.IDLE
        private set

    private var downAt = 0L

    fun down(now: Long) {
        state = State.PRESSED
        downAt = now
    }

    /** The finger moved past the slop: a drag from here on. */
    fun moved() {
        if (state == State.PRESSED || state == State.FILLING) state = State.DRAGGING
    }

    /** True exactly once: when the hold has lasted long enough to stop. */
    fun tick(now: Long): Boolean {
        if (state != State.PRESSED && state != State.FILLING) return false
        val held = now - downAt
        if (held >= armMs + fillMs) {
            state = State.FIRED
            return true
        }
        if (held >= armMs) state = State.FILLING
        return false
    }

    /** The ring, 0–1 (0 until [armMs]). */
    fun progress(now: Long): Float = when (state) {
        State.PRESSED, State.FILLING -> ((now - downAt - armMs).toFloat() / fillMs).coerceIn(0f, 1f)
        State.FIRED -> 1f
        else -> 0f
    }

    /** When [tick] next has something to do; null when nothing is pressed. */
    fun nextAt(): Long? = when (state) {
        State.PRESSED -> downAt + armMs
        State.FILLING -> downAt + armMs + fillMs
        else -> null
    }

    fun up(now: Long): Release {
        // A frame that would have closed the ring may never have come (a busy main thread).
        val closedNow = tick(now)
        val r = when (state) {
            State.PRESSED -> Release.TAP
            State.FILLING -> Release.CANCELLED
            State.DRAGGING -> Release.DRAG
            State.FIRED -> if (closedNow) Release.STOP else Release.STOPPED
            State.IDLE -> Release.NONE
        }
        state = State.IDLE
        return r
    }

    fun cancel() {
        state = State.IDLE
    }

    companion object {
        /** Under the system's long-press timeout (400 ms): a held finger shows the ring promptly. */
        const val ARM_MS = 300L

        /** The ring's fill: a full second from touch to stop, long enough not to happen by accident. */
        const val FILL_MS = 700L
    }
}

/**
 * Where the ball's window goes, in px. The ball sits [edge] from the screen side like the app's
 * floating controls; folding starts on the capsule's end circle.
 */
internal object BallGeometry {
    /** The window margin EdgeSnap keeps from the safe area so the ball itself is [edge] in. */
    fun margin(edge: Int, pad: Int) = edge - pad

    /** The x of a window [windowW] wide centred on a [screenW] screen (Gravity.CENTER_HORIZONTAL). */
    fun centredX(screenW: Int, windowW: Int) = (screenW - windowW) / 2

    /** The left edge of the circle at the end on [side] of a rect [left]..[right] ([ball] across). */
    fun circleLeft(left: Int, right: Int, ball: Int, side: Side) = if (side == Side.RIGHT) right - ball else left

    /**
     * The ball window over a circle at ([circleLeft], [circleTop]) in the surface of a capsule
     * window at ([windowX], [windowY]) whose root pads the surface by [padX], [padTop]; [pad] is
     * the ball window's own room round the ball.
     */
    fun ballOver(windowX: Int, windowY: Int, padX: Int, padTop: Int, circleLeft: Int, circleTop: Int, pad: Int) =
        WindowPos(windowX + padX + circleLeft - pad, windowY + padTop + circleTop - pad)
}
