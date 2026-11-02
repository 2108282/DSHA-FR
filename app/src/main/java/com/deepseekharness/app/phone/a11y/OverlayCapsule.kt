package com.deepseekharness.app.phone.a11y

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.Interpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.deepseekharness.app.pet.Side
import kotlin.math.roundToInt

/** What the capsule and its card show; ControlOverlay builds it from the task and the line. */
internal data class CapsuleState(
    /** The header's line: what DSH does now, or 已暂停 / 等你确认 / 已停止. */
    val line: String,
    val look: MarkLook,
    val paused: Boolean,
    val stopped: Boolean,
    /** The line opens the card (not while a confirm waits, nor once stopped): it shows its chevron. */
    val expandable: Boolean,
    /** Any limit lifted: a lock before the line, a row in the card. */
    val unlocked: Boolean,
    /** The card's 已解除限制 row (which limits). */
    val unlockedLine: CharSequence?,
    /** The card's first line under the header: 「微信 · 免确认」. */
    val meta: String?,
    /** The steps before this one, newest first. */
    val steps: List<String>,
)

/**
 * The control's open window (TYPE_ACCESSIBILITY_OVERLAY): one solid surface that is a capsule —
 * the default, the line and 暂停 / 停止 — or the card that grows out of it, and shrinks to a circle
 * when it folds into the ball. The surface is one view drawing one rounded rect that the morphs
 * animate ([OverlaySurface]); the rows inside never re-flow during a fold, they are cut by the
 * outline and fade. The header row is the same in both forms, so 停止 stays at the right end.
 *
 * Main thread only. The owner ([ControlOverlay]) adds and removes the window, decides the form and
 * runs the ball; this class draws, lays out and morphs.
 */
internal class OverlayCapsule(
    private val ctx: Context,
    private val c: OverlayPalette,
    private val strings: Strings,
    private val actions: Actions,
    /** Applies [params] to the window (after the surface changed size). */
    private val relayout: () -> Unit,
) {
    class Strings(
        val fold: String, val expand: String, val collapse: String,
        val pause: String, val resume: String, val stop: String,
        val steerHint: String, val send: String,
        val unlocksDescription: String, val review: String,
        val foldRow: String, val holdHint: String,
    )

    interface Actions {
        /** Any finger came down on the window. */
        fun touched()
        fun mark()
        fun line()
        fun pause()
        fun stop()
        fun unlocks()
        fun steer()
        fun send()
        fun inputDone()
    }

    enum class Shape { CAPSULE, CARD }

    private val headerH = ctx.dp(CapsuleMetrics.HEIGHT_DP)
    private val ball = headerH

    /** Room round the surface for its shadow (transparent, inside the window). */
    val padX = ctx.dp(PAD_X_DP)
    val padTop = ctx.dp(PAD_TOP_DP)
    val padBottom = ctx.dp(PAD_BOTTOM_DP)

    // ---- header -----------------------------------------------------------------------------------

    val mark = MarkView(ctx, c).apply {
        contentDescription = strings.fold
        foreground = RippleDrawable(ColorStateList.valueOf(c.ripple), null,
            InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }, ctx.dp(4)))
        setOnClickListener { actions.mark() }
    }

    private val lock = GlyphView(ctx, Glyph.LOCK, CapsuleMetrics.LOCK_DP.toFloat()).apply {
        color = c.danger
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    val text = TextView(ctx).apply {
        textSize = 14f
        setTextColor(c.ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val chevron = GlyphView(ctx, Glyph.CHEVRON, CapsuleMetrics.CHEVRON_DP.toFloat()).apply {
        color = c.faint
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The line with its lock and chevron: one key that opens and closes the card. */
    private val lineKey = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(0, 0, ctx.dp(CapsuleMetrics.LINE_END_DP), 0)
        foreground = RippleDrawable(ColorStateList.valueOf(c.ripple), null,
            InsetDrawable(GradientDrawable().apply { cornerRadius = ctx.dpf(18f); setColor(Color.WHITE) }, 0, ctx.dp(6), 0, ctx.dp(6)))
        addView(lock, LinearLayout.LayoutParams(ctx.dp(CapsuleMetrics.LOCK_DP), ctx.dp(CapsuleMetrics.LOCK_DP)).apply { marginEnd = ctx.dp(CapsuleMetrics.LOCK_GAP_DP) })
        addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(chevron, LinearLayout.LayoutParams(ctx.dp(CapsuleMetrics.CHEVRON_DP), ctx.dp(CapsuleMetrics.CHEVRON_DP)).apply { marginStart = ctx.dp(CapsuleMetrics.CHEVRON_GAP_DP) })
        setOnClickListener { actions.line() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                    if (shape == Shape.CARD) strings.collapse else strings.expand))
            }
        }
    }

    val pause = RoundKey(ctx, CapsuleMetrics.DISC_DP, 18).apply {
        disc = c.tonal
        ink = c.ink
        setRipple(c.ripple)
        contentDescription = strings.pause
        setOnClickListener { actions.pause() }
    }

    val stop = RoundKey(ctx, CapsuleMetrics.DISC_DP, 16).apply {
        disc = c.dangerSoft
        ink = c.danger
        setGlyph(Glyph.STOP, animated = false)
        setRipple(c.ripple)
        contentDescription = strings.stop
        setOnClickListener { actions.stop() }
    }

    private val keyGap = View(ctx)

    private val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val key = ctx.dp(CapsuleMetrics.KEY_DP)
        addView(mark, LinearLayout.LayoutParams(key, key))
        addView(lineKey, LinearLayout.LayoutParams(0, key, 1f))
        addView(pause, LinearLayout.LayoutParams(key, key))
        addView(keyGap, LinearLayout.LayoutParams(ctx.dp(CapsuleMetrics.KEY_GAP_DP), 1))
        addView(stop, LinearLayout.LayoutParams(key, key))
    }

    // ---- card body ----------------------------------------------------------------------------------

    /** Where the card's text starts: level with the line, the mark's column as a gutter. */
    private val column = ctx.dp(CapsuleMetrics.KEY_DP + 4)
    private val gutter = ctx.dpf(CapsuleMetrics.KEY_DP / 2f)

    private val meta = TextView(ctx).apply {
        textSize = 12f
        setTextColor(c.faint)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(column, 0, ctx.dp(16), ctx.dp(4))
    }

    private val stepRows = List(StepHistory.CAPACITY) { StepRow() }

    private val unlockText = TextView(ctx).apply {
        textSize = 13f
        setTextColor(c.onDangerSoft)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.15f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val unlocks = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(48)
        isClickable = true
        isFocusable = true
        contentDescription = strings.unlocksDescription
        val r = ctx.dpf(INNER_RADIUS_DP)
        background = GradientDrawable().apply { cornerRadius = r; setColor(c.dangerSoft) }
        foreground = RippleDrawable(ColorStateList.valueOf(c.ripple), null, GradientDrawable().apply { cornerRadius = r; setColor(Color.WHITE) })
        setPadding(0, ctx.dp(8), ctx.dp(12), ctx.dp(8))
        // The lock in the mark's column (the row sits 12 dp in, its text level with the card's).
        val inset = ctx.dp(BODY_INSET_DP)
        addView(GlyphView(ctx, Glyph.LOCK, 16f).apply { color = c.danger },
            LinearLayout.LayoutParams(ctx.dp(CapsuleMetrics.KEY_DP) - 2 * inset, ctx.dp(16)).apply { marginEnd = column - (ctx.dp(CapsuleMetrics.KEY_DP) - inset) })
        addView(unlockText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(ctx).apply {
            text = strings.review
            textSize = 13f
            setTextColor(c.danger)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(ctx.dp(12), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        addView(GlyphView(ctx, Glyph.CHEVRON, 12f).apply { color = c.danger; turn = -90f },
            LinearLayout.LayoutParams(ctx.dp(12), ctx.dp(12)).apply { marginStart = ctx.dp(2) })
        setOnClickListener { actions.unlocks() }
    }

    /** 插话: a field that only takes the keyboard once tapped (the window is not focusable till then). */
    val input: EditText = object : EditText(ctx) {
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP) { actions.inputDone(); return true }
            return super.onKeyPreIme(keyCode, event)
        }
    }.apply {
        textSize = 14f
        setTextColor(c.ink)
        setHintTextColor(c.faint)
        hint = strings.steerHint
        background = null
        setPadding(ctx.dp(16), ctx.dp(11), ctx.dp(8), ctx.dp(11))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        maxLines = 3
        filters = arrayOf(InputFilter.LengthFilter(2000))
        setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { actions.send(); true } else false }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { syncSend() }
        })
    }

    private val send = RoundKey(ctx, 32, 16).apply {
        setGlyph(Glyph.SEND, animated = false)
        setRipple(c.ripple)
        contentDescription = strings.send
        setOnClickListener { actions.send() }
    }

    /** Taps on the field before typing starts go to [Actions.steer], not the text. */
    var editing = false
        private set

    private val steer = object : LinearLayout(ctx) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = !editing
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!editing && ev.actionMasked == MotionEvent.ACTION_UP) actions.steer()
            return !editing || super.onTouchEvent(ev)
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(44)
        background = GradientDrawable().apply { cornerRadius = ctx.dpf(22f); setColor(c.field) }
        setPadding(0, 0, ctx.dp(6), 0)
        addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(send, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
    }

    private val footer = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(48)
        isClickable = true
        isFocusable = true
        foreground = RippleDrawable(ColorStateList.valueOf(c.ripple), null,
            InsetDrawable(GradientDrawable().apply { cornerRadius = ctx.dpf(INNER_RADIUS_DP); setColor(Color.WHITE) }, ctx.dp(BODY_INSET_DP), 0, ctx.dp(BODY_INSET_DP), 0))
        contentDescription = "${strings.foldRow}. ${strings.holdHint}"
        addView(GlyphView(ctx, Glyph.BALL, 14f).apply { color = c.faint },
            LinearLayout.LayoutParams(ctx.dp(CapsuleMetrics.KEY_DP), ctx.dp(14)).apply { marginEnd = column - ctx.dp(CapsuleMetrics.KEY_DP) })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = strings.foldRow; textSize = 13f; setTextColor(c.muted) })
            addView(TextView(ctx).apply { text = strings.holdHint; textSize = 11f; setTextColor(c.faint); setPadding(0, ctx.dp(1), 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dp(16) })
        setOnClickListener { actions.mark() }
    }

    private val body = object : LinearLayout(ctx) {
        private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.faint; alpha = 0x40; strokeWidth = ctx.dpf(1f) }

        /** The timeline: from under the mark down to the last step's dot. */
        override fun dispatchDraw(canvas: Canvas) {
            val last = stepRows.lastOrNull { it.visibility == View.VISIBLE && it.parent === this }
            if (last != null) canvas.drawLine(gutter, ctx.dpf(2f), gutter, last.top + last.height / 2f, rule)
            super.dispatchDraw(canvas)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setWillNotDraw(false)
        setPadding(0, 0, 0, ctx.dp(8))
        visibility = View.GONE
        addView(meta)
        stepRows.forEach { addView(it) }
        val m = ctx.dp(BODY_INSET_DP)
        addView(unlocks, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(m, ctx.dp(8), m, 0) })
        addView(steer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(m, ctx.dp(10), m, 0) })
        addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
    }

    /** One past step: a dot on the timeline in the mark's column, the step level with the line. */
    private inner class StepRow : TextView(ctx) {
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.faint }

        init {
            textSize = 13f
            setTextColor(c.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minHeight = ctx.dp(26)
            setPadding(column, 0, ctx.dp(16), 0)
            visibility = View.GONE
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawCircle(gutter, height / 2f, ctx.dpf(2.5f), dot)
            super.onDraw(canvas)
        }
    }

    // ---- window ---------------------------------------------------------------------------------------

    val surface = OverlaySurface(ctx, c, header, body)

    val root: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) actions.touched()
            return super.dispatchTouchEvent(ev)
        }
    }.apply {
        setPadding(padX, padTop, padX, padBottom)
        clipToPadding = false
        clipChildren = false
        addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && editing) actions.inputDone(); false }
    }

    lateinit var params: android.view.WindowManager.LayoutParams

    // ---- state ------------------------------------------------------------------------------------------

    var shape = Shape.CAPSULE
        private set

    private var morph: ValueAnimator? = null
    private var state: CapsuleState? = null
    private var screenW = 0
    private var screenHdp = 800f
    private val width = CapsuleWidth(slack = ctx.dp(24))

    /** The card's width (and the capsule's widest). */
    private var cardW = 0

    val morphing: Boolean get() = morph != null

    /** A new screen size (first placement, rotation): widths again, at once. */
    fun place(screenW: Int, screenH: Int) {
        this.screenW = screenW
        screenHdp = screenH / ctx.resources.displayMetrics.density
        cardW = CapsuleMetrics.maxPx(screenW, ctx.resources.displayMetrics.density)
        surface.cardW = cardW
        cancelMorph()
        width.reset()
        state?.let { width.force(wantedWidth(it)) }
        settle(shape)
    }

    /** Shows [s]; [animated] cross-fades the line and lets the capsule follow its width. */
    fun render(s: CapsuleState, animated: Boolean, now: Long) {
        val old = state
        state = s
        val anim = animated && OverlayMotion.on()
        setLine(s.line, anim && old != null)
        lineKey.contentDescription = s.line
        mark.setLook(s.look, anim)
        lock.visibility = if (s.unlocked && !s.stopped) View.VISIBLE else View.GONE
        // The open card keeps its chevron (it closes it); a capsule that can't open hides it.
        chevron.visibility = if (s.expandable || shape == Shape.CARD) View.VISIBLE else View.GONE
        lineKey.isClickable = s.expandable || shape == Shape.CARD
        val keys = if (s.stopped) View.GONE else View.VISIBLE
        pause.visibility = keys
        keyGap.visibility = keys
        stop.visibility = keys
        pause.setGlyph(if (s.paused) Glyph.PLAY else Glyph.PAUSE, anim)
        pause.disc = if (s.paused) c.accentSoft else c.tonal
        pause.ink = if (s.paused) c.accent else c.ink
        pause.contentDescription = if (s.paused) strings.resume else strings.pause
        mark.contentDescription = strings.fold
        mark.isClickable = !s.stopped
        bindBody(s)
        // The width: a state change (stopped) at once; a new line grows now, shrinks later.
        val wanted = wantedWidth(s)
        val changed = if (old == null || old.stopped != s.stopped || old.unlocked != s.unlocked || old.expandable != s.expandable) width.force(wanted)
        else width.offer(wanted, now)
        if (shape == Shape.CAPSULE && changed && morph == null) resize(anim)
        if (shape == Shape.CARD && morph == null) refitCard(anim)
    }

    /** When a narrower line may shrink the capsule (the owner re-renders then); null if nothing waits. */
    val shrinkAt: Long? get() = width.shrinkAt

    private fun wantedWidth(s: CapsuleState): Int {
        val density = ctx.resources.displayMetrics.density
        val textPx = text.paint.measureText(s.line)
        val content = CapsuleMetrics.contentDp(textPx / density, s.unlocked && !s.stopped, s.stopped, s.expandable || shape == Shape.CARD) * density
        return CapsuleMetrics.widthPx(content, screenW, density)
    }

    private fun setLine(line: String, animated: Boolean) {
        if (text.text?.toString() == line) return
        text.animate().cancel()
        if (!animated) {
            text.text = line
            text.alpha = 1f
            return
        }
        text.animate().alpha(0f).setDuration(70).setInterpolator(OverlayMotion.emphasized).withEndAction {
            text.text = line
            text.animate().alpha(1f).setDuration(OverlayMotion.QUICK_MS).setInterpolator(OverlayMotion.emphasized).start()
        }.start()
    }

    private fun bindBody(s: CapsuleState) {
        meta.text = s.meta.orEmpty()
        meta.visibility = if (s.meta.isNullOrBlank()) View.GONE else View.VISIBLE
        val n = CapsuleMetrics.steps(screenHdp)
        stepRows.forEachIndexed { i, row ->
            val step = s.steps.getOrNull(i)?.takeIf { i < n }
            row.visibility = if (step == null) View.GONE else View.VISIBLE
            row.text = step.orEmpty()
        }
        unlocks.visibility = if (s.unlocked && s.unlockedLine != null) View.VISIBLE else View.GONE
        unlockText.text = s.unlockedLine
    }

    private fun syncSend() {
        val has = !input.text.isNullOrBlank()
        send.disc = if (has) c.accent else Color.TRANSPARENT
        send.ink = if (has) c.onAccent else c.faint
        send.isEnabled = has
    }

    init {
        syncSend()
        // The field grows a line while typing: the card follows.
        body.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (shape == Shape.CARD && morph == null && bottom - top != oldBottom - oldTop) root.post { if (shape == Shape.CARD && morph == null) refitCard(false) }
        }
    }

    // ---- typing ---------------------------------------------------------------------------------------

    fun startEditing() {
        editing = true
        input.isFocusableInTouchMode = true
        input.requestFocus()
    }

    fun stopEditing() {
        editing = false
        input.clearFocus()
    }

    // ---- morphs ---------------------------------------------------------------------------------------

    /** The steady layout of [to]: extent, rect, what shows, the window's size. */
    fun settle(to: Shape) {
        morph = null
        surface.pin(null)
        header.alpha = 1f
        shape = to
        val capW = width.width.takeIf { it > 0 } ?: cardW
        when (to) {
            Shape.CAPSULE -> {
                body.visibility = View.GONE
                body.alpha = 0f
                body.translationY = 0f
                chevron.turn = 0f
                surface.setExtent(capW, headerH)
                surface.setRect(0f, 0f, capW.toFloat(), headerH.toFloat())
            }
            Shape.CARD -> {
                body.visibility = View.VISIBLE
                body.alpha = 1f
                body.translationY = 0f
                chevron.turn = 180f
                val h = surface.cardHeight()
                surface.setExtent(cardW, h)
                surface.setRect(0f, 0f, cardW.toFloat(), h.toFloat())
            }
        }
        fitWindow()
    }

    /** Ends a morph where it stands and lays out the form it was in. */
    fun cancelMorph() {
        val m = morph
        morph = null
        m?.cancel()
        settle(shape)
    }

    /** The capsule grows into the card: down and out, the body fading in behind. */
    fun expand(animated: Boolean, then: () -> Unit = {}) {
        val from = steadyRectInCard()
        morph?.let { it.cancel(); morph = null }
        // From a collapse cut short, carry on from where the body and the chevron are.
        val wasOpen = body.visibility == View.VISIBLE
        val startAlpha = if (wasOpen) body.alpha else 0f
        val startTurn = if (wasOpen) chevron.turn else 0f
        shape = Shape.CARD
        body.alpha = startAlpha
        body.visibility = View.VISIBLE
        val h = surface.cardHeight()
        val start = if (wasOpen && surface.extentW == cardW) RectF(surface.rect) else from
        surface.setExtent(cardW, h)
        surface.setRect(start)
        fitWindow()
        val shift = ctx.dpf(-6f)
        animate(RectF(0f, 0f, cardW.toFloat(), h.toFloat()), OverlayMotion.MORPH_MS, OverlayMotion.emphasized, animated, frame = { t ->
            val k = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
            body.alpha = startAlpha + (1f - startAlpha) * k
            body.translationY = shift * (1f - k) * (1f - startAlpha)
            chevron.turn = startTurn + (180f - startTurn) * t
        }) { settle(Shape.CARD); then() }
    }

    /** The card shrinks back into the capsule, the body fading first. */
    fun collapse(animated: Boolean, then: () -> Unit = {}) {
        if (shape != Shape.CARD) { then(); return }
        morph?.let { it.cancel(); morph = null }
        val capW = width.width.takeIf { it > 0 } ?: cardW
        val left = (surface.extentW - capW) / 2f
        val startAlpha = body.alpha
        animate(RectF(left, 0f, left + capW, headerH.toFloat()), OverlayMotion.MORPH_MS, OverlayMotion.emphasized, animated, frame = { t ->
            body.alpha = startAlpha * (1f - t / 0.4f).coerceIn(0f, 1f)
            chevron.turn = 180f * (1f - t)
        }) { settle(Shape.CAPSULE); then() }
    }

    /** The capsule follows a new width, centred, its keys travelling with its end. */
    private fun resize(animated: Boolean) {
        val to = width.width
        val from = surface.rect.width().roundToInt()
        if (to == from || to <= 0) return
        val extent = maxOf(to, from)
        val start = (extent - from) / 2f
        surface.setExtent(extent, headerH)
        surface.setRect(start, 0f, start + from, headerH.toFloat())
        fitWindow()
        val left = (extent - to) / 2f
        animate(RectF(left, 0f, left + to, headerH.toFloat()), OverlayMotion.RESIZE_MS, OverlayMotion.glide, animated) { settle(Shape.CAPSULE) }
    }

    /** The open card's height follows its content (a step more, a lifted limit, a second line typed). */
    private fun refitCard(animated: Boolean) {
        val h = surface.cardHeight()
        if (h == surface.rect.height().roundToInt() && surface.extentW == cardW) return
        val from = RectF(surface.rect)
        surface.setExtent(cardW, maxOf(h, from.bottom.roundToInt()))
        fitWindow()
        animate(RectF(0f, 0f, cardW.toFloat(), h.toFloat()), OverlayMotion.RESIZE_MS, OverlayMotion.glide, animated) { settle(Shape.CARD) }
    }

    /** The end circle on [side] of the surface now, in the surface's own coordinates (left, top). */
    fun endCircle(side: Side): Pair<Int, Int> {
        val r = surface.rect
        return BallGeometry.circleLeft(r.left.roundToInt(), r.right.roundToInt(), ball, side) to r.top.roundToInt()
    }

    /** The end circle a steady capsule would have on [side] (left, top in the surface). */
    fun capsuleEndCircle(side: Side): Pair<Int, Int> {
        val capW = width.width.takeIf { it > 0 } ?: cardW
        val left = (surface.extentW - capW) / 2
        return BallGeometry.circleLeft(left, left + capW, ball, side) to 0
    }

    /**
     * Folding: the surface narrows (and, from the card, shortens) to the circle at its end on
     * [side], the rows pinned where they were, fading in the first 40 %. [then] when it is the
     * ball's size and place.
     */
    fun foldTo(side: Side, animated: Boolean, then: () -> Unit) {
        morph?.let { it.cancel(); morph = null }
        val r = RectF(surface.rect)
        surface.pin(RectF(r.left, r.top, r.right, r.top + headerH))
        val left = BallGeometry.circleLeft(r.left.roundToInt(), r.right.roundToInt(), ball, side).toFloat()
        val bodyAlpha = body.alpha
        animate(RectF(left, r.top, left + ball, r.top + headerH), OverlayMotion.MORPH_MS, OverlayMotion.emphasized, animated, frame = { t ->
            header.alpha = (1f - t / 0.4f).coerceIn(0f, 1f)
            body.alpha = bodyAlpha * (1f - t / 0.3f).coerceIn(0f, 1f)
        }) { then() }
    }

    /**
     * The capsule as the circle on [side], its row hidden: what [unfoldFrom] starts from. The owner
     * shows the window like this (transparent) while the ball is still on its way, so the window
     * has drawn before the swap and the morph starts on its first frame.
     */
    fun prepareUnfold(side: Side) {
        morph?.let { it.cancel(); morph = null }
        settle(Shape.CAPSULE)
        val full = RectF(surface.rect)
        surface.pin(RectF(full))
        val left = BallGeometry.circleLeft(full.left.roundToInt(), full.right.roundToInt(), ball, side).toFloat()
        surface.setRect(left, 0f, left + ball, headerH.toFloat())
        header.alpha = 0f
    }

    /** Unfolding: from the circle on [side] ([prepareUnfold]) the capsule grows out, its row fading in over the last half. */
    fun unfoldFrom(side: Side, animated: Boolean, then: () -> Unit) {
        if (morph != null || shape != Shape.CAPSULE || surface.rect.width().roundToInt() != ball) prepareUnfold(side)
        val capW = width.width.takeIf { it > 0 } ?: cardW
        val full = RectF(0f, 0f, capW.toFloat(), headerH.toFloat())
        animate(full, OverlayMotion.MORPH_MS, OverlayMotion.emphasized, animated, frame = { t ->
            header.alpha = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
        }) { settle(Shape.CAPSULE); then() }
    }

    private fun steadyRectInCard(): RectF {
        val capW = width.width.takeIf { it > 0 } ?: cardW
        val left = (cardW - capW) / 2f
        return RectF(left, 0f, left + capW, headerH.toFloat())
    }

    private fun animate(to: RectF, ms: Long, curve: Interpolator, animated: Boolean, frame: ((Float) -> Unit)? = null, then: () -> Unit) {
        val from = RectF(surface.rect)
        if (!animated || !OverlayMotion.on()) {
            surface.setRect(to)
            frame?.invoke(1f)
            then()
            return
        }
        val now = RectF()
        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                now.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t)
                surface.setRect(now)
                frame?.invoke(t)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    if (morph === animation) morph = null
                    then()
                }
            })
            start()
        }
    }

    private fun fitWindow() {
        if (!::params.isInitialized) return
        val w = surface.extentW + 2 * padX
        val h = surface.extentH + padTop + padBottom
        if (params.width == w && params.height == h) return
        params.width = w
        params.height = h
        relayout()
    }

    companion object {
        /** The window's room round the surface for its shadow. */
        const val PAD_X_DP = 12
        const val PAD_TOP_DP = 4
        const val PAD_BOTTOM_DP = 16

        /** Rows inside the card sit this far in; their corners are the card's less that (24 − 12). */
        const val BODY_INSET_DP = 12
        const val INNER_RADIUS_DP = 12f
    }
}

/**
 * One rounded rect of solid surface ([rect], radius 24 dp) that the morphs animate, with the
 * header and the card body inside, cut to its outline. The view itself is [extentW] × [extentH]
 * (the window's content); the rect moves inside it. The header follows the rect's width, or stays
 * where [pin] put it while a fold cuts it; the body is laid out once at the card's width.
 */
internal class OverlaySurface(context: Context, private val c: OverlayPalette, private val header: View, private val body: View) : ViewGroup(context) {
    private val painter = SurfacePainter(context)
    private val radius = context.dpf(CapsuleMetrics.RADIUS_DP.toFloat())
    private val headerH = context.dp(CapsuleMetrics.HEIGHT_DP)

    /** The body starts this far up into the header's lower margin: its first line reads as the line's second. */
    private val overlap = context.dp(6)

    val rect = RectF()
    private var pinned: RectF? = null

    var extentW = 0
        private set
    var extentH = 0
        private set

    /** The body's width: the card's. */
    var cardW = 0

    init {
        setWillNotDraw(false)
        clipChildren = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt(), radius)
            }
        }
        softShadow(c, 6f)
        addView(header)
        addView(body)
    }

    fun setExtent(w: Int, h: Int) {
        if (w == extentW && h == extentH) return
        extentW = w
        extentH = h
        requestLayout()
    }

    fun setRect(r: RectF) = setRect(r.left, r.top, r.right, r.bottom)

    fun setRect(l: Float, t: Float, r: Float, b: Float) {
        val widthChanged = (r - l).roundToInt() != rect.width().roundToInt()
        rect.set(l, t, r, b)
        if (pinned == null) {
            // Laying the header out here, between traversals, clears its pending layout request:
            // measure it first if one is pending (a lock shown, a key hidden), or the next pass
            // would keep the stale measurement.
            if (widthChanged || header.isLayoutRequested) measureHeader()
            layoutHeader()
        }
        invalidateOutline()
        invalidate()
    }

    /** Holds the header at [at] (a fold cuts it there) or lets it follow the rect again (null). */
    fun pin(at: RectF?) {
        pinned = at
        measureHeader()
        layoutHeader()
    }

    /** The card's height for the body as it is now. */
    fun cardHeight(): Int {
        body.measure(MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return headerH - overlap + body.measuredHeight
    }

    private fun headerRect(): RectF = pinned ?: rect

    private fun measureHeader() {
        val w = headerRect().width().roundToInt().coerceAtLeast(0)
        header.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(headerH, MeasureSpec.EXACTLY))
    }

    private fun layoutHeader() {
        val r = headerRect()
        val l = r.left.roundToInt()
        val t = r.top.roundToInt()
        header.layout(l, t, l + header.measuredWidth, t + headerH)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureHeader()
        if (body.visibility != View.GONE) {
            body.measure(MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        setMeasuredDimension(extentW, extentH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        layoutHeader()
        if (body.visibility != View.GONE) {
            val left = (extentW - cardW) / 2
            val top = headerH - overlap
            body.layout(left, top, left + body.measuredWidth, top + body.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        painter.draw(canvas, rect, radius, c)
    }
}
