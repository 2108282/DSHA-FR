package com.deepseekharness.app.phone.a11y

import java.util.EnumSet

/**
 * Which of the control's three forms is due, and when the next one is. Plain Kotlin: the overlay
 * supplies the clock, runs one timer at [nextAt] and animates from what is on screen to [form].
 *
 * - [Form.CAPSULE], the default: one line (what DSH does now) and 暂停 / 停止. It never fades:
 *   it carries the information, on a solid surface.
 * - [Form.CARD]: the capsule grown downwards with the second layer (app, recent steps, lifted
 *   limits, 插话). Opened from the capsule's line; back to the capsule [cardMs] after the last
 *   touch, unless the person is typing.
 * - [Form.BALL]: only the state, docked on a side. The capsule's mark folds it ([foldByHand]),
 *   until the person taps the ball, for the rest of the task. With 「闲置时自动收起」
 *   ([autoFold]) an idle capsule folds after [foldMs]. A ball that is left alone fades to the
 *   闲置时不透明度 after [dimMs]; a touch brings it back.
 *
 * What needs the person's eyes or hands holds the form and stops every count: a confirm card, a
 * handoff card, 插话 being typed, the 已停止 note. All but typing fold the card back to the
 * capsule (the card would compete with the confirm card for attention); an automatic fold opens
 * for the first three ([OPENING]), a fold by hand stays. When the last hold goes, the counts
 * start over. Status lines from the agent are not touches.
 */
class FoldClock(val dimMs: Long = DIM_MS, val foldMs: Long = FOLD_MS, val cardMs: Long = CARD_MS) {
    enum class Form { BALL, CAPSULE, CARD }

    enum class Hold { CONFIRM, HANDOFF, EDITING, STOPPED }

    enum class Tick { NONE, DIM, FOLD, COLLAPSE }

    /** 「闲置时自动收起」. */
    var autoFold = false
        private set

    /** Between [start] and [reset]: a control is up (in any form). */
    var running = false
        private set

    var form = Form.CAPSULE
        private set

    val folded: Boolean get() = form == Form.BALL

    val expanded: Boolean get() = form == Form.CARD

    /** Folded with the capsule's mark: only a tap on the ball opens it. */
    var byHand = false
        private set

    /** The ball is at the idle opacity rather than full (the capsule and the card never are). */
    var dimmed = false
        private set

    var dimAt: Long? = null
        private set

    var foldAt: Long? = null
        private set

    var collapseAt: Long? = null
        private set

    /** The next time [tick] has something to do; null while nothing is due. */
    val nextAt: Long? get() = listOfNotNull(dimAt, foldAt, collapseAt).minOrNull()

    private val holds: EnumSet<Hold> = EnumSet.noneOf(Hold::class.java)

    val held: Boolean get() = holds.isNotEmpty()

    fun isHeld(hold: Hold): Boolean = hold in holds

    /** The control came up, as the capsule, at full. */
    fun start(now: Long) {
        if (running) return
        running = true
        form = Form.CAPSULE
        byHand = false
        dimmed = false
        arm(now)
    }

    /** A finger on the control (any form): back to full, the counts start over. True when it was dimmed. */
    fun touch(now: Long): Boolean {
        if (!running) return false
        val was = dimmed
        dimmed = false
        arm(now)
        return was
    }

    /** The capsule's line: the card opens. False when it can't (folded, stopped, a confirm up). */
    fun expand(now: Long): Boolean {
        if (!running || form != Form.CAPSULE || holds.any { it in COLLAPSING }) return false
        form = Form.CARD
        arm(now)
        return true
    }

    /** The card's line again: back to the capsule. */
    fun collapse(now: Long): Boolean {
        if (!running || form != Form.CARD || Hold.EDITING in holds) return false
        form = Form.CAPSULE
        arm(now)
        return true
    }

    /**
     * Full and as it is until [release]. True when the form changed: an automatic fold opened
     * ([OPENING] holds), or the card folded back to the capsule ([COLLAPSING] holds).
     */
    fun hold(hold: Hold, @Suppress("UNUSED_PARAMETER") now: Long): Boolean {
        holds += hold
        dimmed = false
        dimAt = null
        foldAt = null
        collapseAt = null
        if (!running) return false
        if (form == Form.CARD && hold in COLLAPSING) {
            form = Form.CAPSULE
            return true
        }
        if (form == Form.BALL && !byHand && hold in OPENING) {
            form = Form.CAPSULE
            return true
        }
        return false
    }

    /** [hold] is over; with nothing else holding, the counts start again. */
    fun release(hold: Hold, now: Long) {
        if (!holds.remove(hold)) return
        arm(now)
    }

    /** The capsule's mark (or the card's); true when it folded. Stays folded until [unfold]. */
    fun foldByHand(now: Long): Boolean {
        if (!running || form == Form.BALL || Hold.STOPPED in holds) return false
        form = Form.BALL
        byHand = true
        dimmed = false
        arm(now)
        return true
    }

    /** A tap on the ball; true when it opened (to the capsule, never straight to the card). */
    fun unfold(now: Long): Boolean {
        if (!running || form != Form.BALL) return false
        form = Form.CAPSULE
        byHand = false
        dimmed = false
        arm(now)
        return true
    }

    /** 「闲置时自动收起」 switched (applies at once to the control on screen). */
    fun setAutoFold(on: Boolean, now: Long) {
        if (autoFold == on) return
        autoFold = on
        arm(now)
    }

    /** The timer went off: what changes now. */
    fun tick(now: Long): Tick {
        if (!running || held) return Tick.NONE
        collapseAt?.let { at ->
            if (now >= at && form == Form.CARD) {
                form = Form.CAPSULE
                arm(now)
                return Tick.COLLAPSE
            }
        }
        foldAt?.let { at ->
            if (now >= at && form == Form.CAPSULE) {
                form = Form.BALL
                byHand = false
                dimmed = false
                arm(now)
                return Tick.FOLD
            }
        }
        dimAt?.let { at ->
            if (now >= at && form == Form.BALL && !dimmed) {
                dimmed = true
                dimAt = null
                return Tick.DIM
            }
        }
        return Tick.NONE
    }

    /** The control went away. Holds stay with the cards that set them. */
    fun reset() {
        running = false
        form = Form.CAPSULE
        byHand = false
        dimmed = false
        dimAt = null
        foldAt = null
        collapseAt = null
    }

    /** One count per form: the card goes back, an idle capsule folds (自动收起 only), a ball dims. */
    private fun arm(now: Long) {
        dimAt = null
        foldAt = null
        collapseAt = null
        if (!running || held) return
        when (form) {
            Form.CARD -> collapseAt = now + cardMs
            Form.CAPSULE -> if (autoFold) foldAt = now + foldMs
            Form.BALL -> if (!dimmed) dimAt = now + dimMs
        }
    }

    companion object {
        /** 3 s: a ball left alone fades after this long. */
        const val DIM_MS = 3_000L

        /** 3.5 s for 「闲置时自动收起」. */
        const val FOLD_MS = 3_500L

        /** 8 s: long enough to read the card, then it gets out of the way. */
        const val CARD_MS = 8_000L

        /** Holds that ask something of the person: an automatic fold opens for them. */
        val OPENING: Set<Hold> = EnumSet.of(Hold.CONFIRM, Hold.HANDOFF, Hold.EDITING)

        /** Holds that fold the card back to the capsule (typing needs the card). */
        val COLLAPSING: Set<Hold> = EnumSet.of(Hold.CONFIRM, Hold.HANDOFF, Hold.STOPPED)
    }
}
