package com.deepseekharness.app.phone.a11y

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The control's colours: the app's 雾蓝 (mist) neutrals, accent and brick-rose danger
 * (app/lib/ui/theme/palette.dart, surfaces.dart). Every surface is solid — the chrome glass's
 * no-blur fill, made opaque — with the glass's hairline, top rim and cool-ink shadow, so it reads
 * the same over any page and its text keeps its contrast (ink ≥ 13 : 1, faint ≥ 4.6 : 1).
 */
class OverlayPalette(
    val surfaceTop: Int, val surfaceBottom: Int, val hairline: Int, val rim: Int, val shadow: Int,
    val ink: Int, val muted: Int, val faint: Int,
    val accent: Int, val accentSoft: Int, val onAccentSoft: Int, val onAccent: Int,
    val tonal: Int, val field: Int, val ripple: Int, val outline: Int,
    val danger: Int, val dangerSoft: Int, val onDangerSoft: Int,
    val dark: Boolean,
) {
    companion object {
        val LIGHT = OverlayPalette(
            surfaceTop = 0xFFFDFDFF.toInt(), surfaceBottom = 0xFFF5F7FC.toInt(),
            hairline = 0x1A0B152D, rim = 0xE6FFFFFF.toInt(), shadow = 0xFF0B152D.toInt(),
            ink = 0xFF1E232E.toInt(), muted = 0xFF4F5562.toInt(), faint = 0xFF646974.toInt(),
            accent = 0xFF4B67B4.toInt(), accentSoft = 0xFFDEE8FF.toInt(), onAccentSoft = 0xFF213262.toInt(), onAccent = Color.WHITE,
            tonal = 0xFFECEFF6.toInt(), field = 0xFFEEF1F7.toInt(), ripple = 0x1F1E232E, outline = 0x3D0B152D,
            danger = 0xFFA54743.toInt(), dangerSoft = 0xFFFBE5E3.toInt(), onDangerSoft = 0xFF642724.toInt(),
            dark = false,
        )
        val DARK = OverlayPalette(
            surfaceTop = 0xFF2C313C.toInt(), surfaceBottom = 0xFF262A34.toInt(),
            hairline = 0x1AFFFFFF, rim = 0x24FFFFFF, shadow = 0xFF000000.toInt(),
            ink = 0xFFE6E9F0.toInt(), muted = 0xFFB6BBC5.toInt(), faint = 0xFF9A9EA9.toInt(),
            accent = 0xFF97B1F0.toInt(), accentSoft = 0xFF2E3C5F.toInt(), onAccentSoft = 0xFFD8E1F6.toInt(), onAccent = 0xFF121C34.toInt(),
            tonal = 0xFF393E4A.toInt(), field = 0xFF333844.toInt(), ripple = 0x2EFFFFFF, outline = 0x40FFFFFF,
            danger = 0xFFE69089.toInt(), dangerSoft = 0xFF4E2D2C.toInt(), onDangerSoft = 0xFFF8D9D7.toInt(),
            dark = true,
        )

        fun resolve(context: Context, dark: Boolean): OverlayPalette {
            if (dark) return DARK
            return try {
                val m = com.deepseekharness.app.ui.MonetEngine.resolveCurrentPalette(context)
                if (!m.isMonetActive) return LIGHT
                val p = m.primaryColor
                val c = m.containerColor
                OverlayPalette(
                    surfaceTop = androidx.core.graphics.ColorUtils.blendARGB(0xFFFDFDFF.toInt(), p, 0.02f),
                    surfaceBottom = androidx.core.graphics.ColorUtils.blendARGB(0xFFF5F7FC.toInt(), p, 0.05f),
                    hairline = 0x1A0B152D,
                    rim = 0xE6FFFFFF.toInt(),
                    shadow = 0xFF0B152D.toInt(),
                    ink = 0xFF1E232E.toInt(),
                    muted = 0xFF4F5562.toInt(),
                    faint = 0xFF646974.toInt(),
                    accent = p,
                    accentSoft = c,
                    onAccentSoft = androidx.core.graphics.ColorUtils.blendARGB(p, Color.BLACK, 0.45f),
                    onAccent = if (androidx.core.graphics.ColorUtils.calculateLuminance(p) > 0.5) 0xFF10141B.toInt() else Color.WHITE,
                    tonal = androidx.core.graphics.ColorUtils.blendARGB(0xFFECEFF6.toInt(), p, 0.06f),
                    field = androidx.core.graphics.ColorUtils.blendARGB(0xFFEEF1F7.toInt(), p, 0.05f),
                    ripple = 0x1F1E232E,
                    outline = 0x3D0B152D,
                    danger = 0xFFA54743.toInt(),
                    dangerSoft = 0xFFFBE5E3.toInt(),
                    onDangerSoft = 0xFF642724.toInt(),
                    dark = false,
                )
            } catch (_: Throwable) {
                LIGHT
            }
        }
    }
}

/** What the mark in the ball and at the capsule's start says. */
internal enum class MarkLook { RUNNING, PAUSED, WAITING, STOPPED }

internal object OverlayMotion {
    /** The app's emphasized curve (AppMotion.emphasized, M3 0.2 / 0 / 0 / 1). */
    val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** iOS-style long tail for things that travel (AppMotion.glide). */
    val glide = PathInterpolator(0.32f, 0.72f, 0f, 1f)

    /** Structural changes: fold, unfold, capsule ↔ card (AppMotion.emphasized). */
    const val MORPH_MS = 340L

    /** The capsule following a longer or shorter line (AppMotion.standard). */
    const val RESIZE_MS = 240L

    /** Cross-fades of text and marks (AppMotion.quick). */
    const val QUICK_MS = 160L

    /** The running mark's breath (AppMotion.breath). */
    const val BREATH_MS = 2_400L

    /** 系统设置 → 移除动画 sets the animator scale to 0. */
    fun on(): Boolean = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled() else true
}

internal fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

internal fun Context.dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

/**
 * The control's line glyphs, on a 24-unit grid, drawn with a round-capped 1.75-unit stroke unless
 * filled: Phosphor-like, the weight of the app's own icons.
 */
internal enum class Glyph(val filled: Boolean) {
    PAUSE(true), PLAY(true), STOP(true), CHEVRON(false), LOCK(false), SEND(false), BALL(false);

    fun path(): Path = Path().apply {
        when (this@Glyph) {
            PAUSE -> {
                addRoundRect(RectF(7f, 5.5f, 10.5f, 18.5f), 1.5f, 1.5f, Path.Direction.CW)
                addRoundRect(RectF(13.5f, 5.5f, 17f, 18.5f), 1.5f, 1.5f, Path.Direction.CW)
            }
            PLAY -> {
                // A rounded triangle, its optical centre on the grid's centre.
                moveTo(9f, 6.2f)
                cubicTo(9f, 5.4f, 9.9f, 4.9f, 10.6f, 5.4f)
                lineTo(18.2f, 10.8f)
                cubicTo(18.8f, 11.2f, 18.8f, 12.8f, 18.2f, 13.2f)
                lineTo(10.6f, 18.6f)
                cubicTo(9.9f, 19.1f, 9f, 18.6f, 9f, 17.8f)
                close()
            }
            STOP -> addRoundRect(RectF(7f, 7f, 17f, 17f), 2.5f, 2.5f, Path.Direction.CW)
            CHEVRON -> {
                moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f)
            }
            LOCK -> {
                // An open padlock: the shackle swung up off the right post.
                addRoundRect(RectF(5f, 11f, 19f, 20.5f), 2.5f, 2.5f, Path.Direction.CW)
                moveTo(8.5f, 11f); lineTo(8.5f, 7.5f)
                cubicTo(8.5f, 3.5f, 14.5f, 2.8f, 15.4f, 6.6f)
                moveTo(12f, 14.5f); lineTo(12f, 17f)
            }
            SEND -> {
                moveTo(12f, 19f); lineTo(12f, 5.5f)
                moveTo(6.5f, 11f); lineTo(12f, 5.5f); lineTo(17.5f, 11f)
            }
            BALL -> addCircle(12f, 12f, 6.5f, Path.Direction.CW)
        }
    }

    companion object {
        private val cache = HashMap<Glyph, Path>()
        private val m = Matrix()
        private val out = Path()

        /** Draws [g] [size] px square centred on ([cx], [cy]) with [paint] (its colour and alpha as set). */
        fun draw(canvas: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, paint: Paint, rotation: Float = 0f) {
            val base = cache.getOrPut(g) { g.path() }
            val s = size / 24f
            m.reset()
            m.setScale(s, s)
            m.postTranslate(cx - size / 2, cy - size / 2)
            if (rotation != 0f) m.postRotate(rotation, cx, cy)
            base.transform(m, out)
            if (g.filled) {
                paint.style = Paint.Style.FILL
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.75f * s
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(out, paint)
        }
    }
}

/**
 * The state mark, shared by the ball and the capsule's start so the two read as one object: a
 * ring with a dot that breathes while DSH works (the engine page's ring), two bars when paused,
 * three dots when it waits for the person, a rounded square when stopped. 22 dp across.
 */
internal class MarkPainter(private val context: Context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    /** [phase] 0–1: the breath (1 = full ring). [alpha] multiplies everything. */
    fun draw(canvas: Canvas, cx: Float, cy: Float, look: MarkLook, c: OverlayPalette, phase: Float, alpha: Float) {
        if (alpha <= 0f) return
        fun a(v: Float) = (255 * v * alpha).roundToInt().coerceIn(0, 255)
        ring.strokeWidth = context.dpf(2f)
        val r = context.dpf(10f)
        when (look) {
            MarkLook.RUNNING -> {
                ring.color = c.accent
                ring.alpha = a(0.3f + 0.7f * phase)
                canvas.drawCircle(cx, cy, r, ring)
                fill.color = c.accent
                fill.alpha = a(1f)
                canvas.drawCircle(cx, cy, context.dpf(3.5f), fill)
            }
            MarkLook.PAUSED -> {
                ring.color = c.muted
                ring.alpha = a(0.34f)
                canvas.drawCircle(cx, cy, r, ring)
                fill.color = c.muted
                fill.alpha = a(1f)
                val w = context.dpf(2.5f)
                val h = context.dpf(9f)
                val gap = context.dpf(3f)
                rect.set(cx - gap / 2 - w, cy - h / 2, cx - gap / 2, cy + h / 2)
                canvas.drawRoundRect(rect, w / 2, w / 2, fill)
                rect.set(cx + gap / 2, cy - h / 2, cx + gap / 2 + w, cy + h / 2)
                canvas.drawRoundRect(rect, w / 2, w / 2, fill)
            }
            MarkLook.WAITING -> {
                ring.color = c.accent
                ring.alpha = a(1f)
                canvas.drawCircle(cx, cy, r, ring)
                fill.color = c.accent
                fill.alpha = a(1f)
                val step = context.dpf(4.5f)
                val dot = context.dpf(1.6f)
                for (i in -1..1) canvas.drawCircle(cx + i * step, cy, dot, fill)
            }
            MarkLook.STOPPED -> {
                ring.color = c.muted
                ring.alpha = a(0.34f)
                canvas.drawCircle(cx, cy, r, ring)
                fill.color = c.muted
                fill.alpha = a(1f)
                val s = context.dpf(8f)
                rect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
                canvas.drawRoundRect(rect, context.dpf(2f), context.dpf(2f), fill)
            }
        }
    }
}

/**
 * Paints the solid surface into a rounded rect: a faint top-to-bottom fall of the surface colour,
 * a top rim of light (the glass's highlight, which gives the dark surface its edge) and a hairline.
 */
internal class SurfacePainter(private val context: Context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var shadedTop = Float.NaN
    private var shadedWith: OverlayPalette? = null

    fun draw(canvas: Canvas, rect: RectF, radius: Float, c: OverlayPalette) {
        // The fall of colour is over the capsule's own height, so a card is no darker at its foot.
        val top = rect.top
        val span = context.dpf(CapsuleMetrics.HEIGHT_DP.toFloat())
        if (top != shadedTop || c !== shadedWith) {
            fill.shader = LinearGradient(0f, top, 0f, top + span, c.surfaceTop, c.surfaceBottom, Shader.TileMode.CLAMP)
            // The rim: light on the upper edge, gone by a third of the capsule's height.
            rim.shader = LinearGradient(0f, top, 0f, top + span / 3, c.rim, c.rim and 0x00FFFFFF, Shader.TileMode.CLAMP)
            shadedTop = top
            shadedWith = c
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        val hair = context.dpf(1f)
        r.set(rect)
        r.inset(hair / 2, hair / 2)
        rim.strokeWidth = hair
        hairline.strokeWidth = hair
        hairline.color = c.hairline
        canvas.drawRoundRect(r, radius - hair / 2, radius - hair / 2, rim)
        canvas.drawRoundRect(r, radius - hair / 2, radius - hair / 2, hairline)
    }
}

/** A soft cool-ink shadow for a view with an outline (API 28+ tints it; older ones draw grey). */
internal fun View.softShadow(c: OverlayPalette, elevationDp: Float) {
    elevation = context.dpf(elevationDp)
    if (Build.VERSION.SDK_INT >= 28) {
        val ink = c.shadow and 0x00FFFFFF
        outlineSpotShadowColor = ink or (if (c.dark) 0xFF000000.toInt() else 0x80000000.toInt())
        outlineAmbientShadowColor = ink or (if (c.dark) 0xCC000000.toInt() else 0x66000000)
    }
}

/**
 * 暂停 / 继续 / 停止: a 48 dp touch target with a [discDp] disc and a glyph, a ripple over the
 * disc and a small press. Changing the glyph cross-fades it.
 */
internal class RoundKey(context: Context, private val discDp: Int, private val glyphDp: Int) : View(context) {
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var swap: ValueAnimator? = null
    private var swapT = 1f

    var disc: Int = Color.TRANSPARENT
        set(v) { if (field != v) { field = v; invalidate() } }

    var ink: Int = Color.BLACK
        set(v) { if (field != v) { field = v; invalidate() } }

    var glyph: Glyph = Glyph.PAUSE
        private set

    init {
        isClickable = true
        isFocusable = true
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    fun setGlyph(g: Glyph, animated: Boolean) {
        if (g == glyph) return
        glyph = g
        swap?.cancel()
        if (!animated || !OverlayMotion.on()) {
            swapT = 1f
            invalidate()
            return
        }
        swap = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OverlayMotion.QUICK_MS
            interpolator = OverlayMotion.emphasized
            addUpdateListener { swapT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun setRipple(color: Int) {
        val inset = ((context.dp(CapsuleMetrics.KEY_DP) - context.dp(discDp)) / 2)
        val mask = InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }, inset)
        foreground = RippleDrawable(ColorStateList.valueOf(color), null, mask)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val s = if (pressed) 0.92f else 1f
        if (OverlayMotion.on()) animate().scaleX(s).scaleY(s).setDuration(if (pressed) 90 else 200).setInterpolator(OverlayMotion.glide).start()
        else { scaleX = s; scaleY = s }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        discPaint.color = disc
        canvas.drawCircle(cx, cy, context.dpf(discDp / 2f), discPaint)
        glyphPaint.color = ink
        glyphPaint.alpha = (Color.alpha(ink) * swapT).roundToInt()
        val size = context.dpf(glyphDp.toFloat()) * (0.7f + 0.3f * swapT)
        Glyph.draw(canvas, glyph, cx, cy, size, glyphPaint)
    }
}

/** A glyph on its own (the lock, the chevron, the steer field's send arrow), [sizeDp] square. */
internal class GlyphView(context: Context, private val glyph: Glyph, private val sizeDp: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var color: Int = Color.BLACK
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Degrees; the chevron turns over as the card opens. */
    var turn: Float = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = color
        Glyph.draw(canvas, glyph, width / 2f, height / 2f, context.dpf(sizeDp), paint, turn)
    }
}

/**
 * The mark at the capsule's start, the ball's twin: breathes while DSH works and visible,
 * cross-fades between looks.
 */
internal class MarkView(context: Context, private val palette: OverlayPalette) : View(context) {
    private val painter = MarkPainter(context)
    private var breath: ValueAnimator? = null
    private var phase = 1f
    private var shown = false
    private var from: MarkLook? = null
    private var fade: ValueAnimator? = null
    private var fadeT = 1f

    var look: MarkLook = MarkLook.RUNNING
        private set

    /** The mark's own opacity, under the view's (the fold fades the row, not this). */
    var markAlpha = 1f
        set(v) { val x = v.coerceIn(0f, 1f); if (field != x) { field = x; invalidate() } }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    fun setLook(next: MarkLook, animated: Boolean) {
        if (next == look) return
        from = look
        look = next
        fade?.cancel()
        syncBreath()
        if (!animated || !OverlayMotion.on()) {
            from = null
            fadeT = 1f
            invalidate()
            return
        }
        fadeT = 0f
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OverlayMotion.QUICK_MS
            interpolator = OverlayMotion.emphasized
            addUpdateListener { fadeT = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { from = null }
            })
            start()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        syncBreath()
    }

    override fun onDetachedFromWindow() {
        shown = false
        syncBreath()
        super.onDetachedFromWindow()
    }

    private fun syncBreath() {
        val want = shown && look == MarkLook.RUNNING && OverlayMotion.on()
        if (want && breath == null) {
            breath = breathing { phase = it; invalidate() }
        } else if (!want && breath != null) {
            breath?.cancel()
            breath = null
            phase = 1f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        from?.let { painter.draw(canvas, cx, cy, it, palette, phase, markAlpha * (1f - fadeT)) }
        painter.draw(canvas, cx, cy, look, palette, phase, markAlpha * if (from != null) fadeT else 1f)
    }
}

/** The breath: 1 → 0 → 1 over [OverlayMotion.BREATH_MS], eased at both ends (a cosine). */
internal fun breathing(onPhase: (Float) -> Unit): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
    duration = OverlayMotion.BREATH_MS
    repeatCount = ValueAnimator.INFINITE
    interpolator = LinearInterpolator()
    addUpdateListener { onPhase((0.5f + 0.5f * cos(2 * PI * (it.animatedValue as Float))).toFloat()) }
    start()
}
