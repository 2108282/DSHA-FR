// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's permission. See THIRD_PARTY_NOTICES.md.
// Local changes: a status control at the top in three forms (ball, capsule, card) on one morphing
// surface; separate confirm and handoff cards; buttons that refuse injected or obscured touches;
// pass-through and hide hooks for the Actuator and screenshots; blocking UserGate calls.

package com.deepseekharness.app.phone.a11y

import com.deepseekharness.app.i18n.AppStrings
import com.deepseekharness.app.R
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.deepseekharness.app.phone.ConfirmRequest
import com.deepseekharness.app.phone.Decision
import com.deepseekharness.app.phone.HandoffCause
import com.deepseekharness.app.phone.HandoffResult
import com.deepseekharness.app.phone.PhoneControl
import com.deepseekharness.app.phone.PhoneRegistry
import com.deepseekharness.app.phone.PhoneUnlocks
import com.deepseekharness.app.phone.UserGate
import com.deepseekharness.app.phone.PhonePromptPresenter
import com.deepseekharness.app.phone.PhonePromptUi
import com.deepseekharness.app.phone.PromptPresentation
import com.deepseekharness.app.phone.MainThreadCall
import com.deepseekharness.app.pet.SafeArea
import com.deepseekharness.app.pet.WindowPos
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The main-screen [UserGate]: TYPE_ACCESSIBILITY_OVERLAY windows owned by the service
 * (no SYSTEM_ALERT_WINDOW). Blocking calls come from the bridge worker; all views live on
 * the main thread.
 *
 * While a task runs the person sees one control at the top, in three forms
 * (docs/design/phone-overlay.md): the capsule with the line and 暂停 / 停止 ([OverlayCapsule]),
 * the card it grows into, and the ball it folds into ([OverlayBall]). [FoldClock] decides the
 * form; this class plays the morphs between them and keeps 停止 one step away in every form.
 */
internal class ControlOverlay(private val service: AccessibilityService) : UserGate, PhonePromptPresenter {
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val a11y = service.getSystemService(AccessibilityManager::class.java)
    private val ime = service.getSystemService(InputMethodManager::class.java)

    /** 暂停 is on: the driver refuses actions until 继续. */
    @Volatile var paused = false
        private set

    /** The person is typing into 插话: actions wait so the keyboard isn't fought over. */
    @Volatile var editing = false
        private set

    /** Debug probe only: draw in the dark (true) or light (false) palette whatever the system says. */
    @Volatile var debugDark: Boolean? = null

    /** Debug probe only: show these lifted limits instead of the person's. */
    @Volatile var debugUnlocks: PhoneUnlocks? = null

    /** Debug probe only: the task's app, as the core would have set it. */
    @Volatile var debugApp: String? = null

    /** Debug probe only: no timer changes the form (screenshots of the card). */
    @Volatile var debugFreeze = false

    /** Debug probe only: let an injected tap reach a guarded button to observe the other checks. */
    @Volatile var debugIgnoreInjectGuard = false

    /** Last guarded-button verdict, for the debug probe. */
    @Volatile var lastGuardVerdict: String? = null
        private set

    // ---- main-thread state ----
    private var statusText: String? = null
    private var line: StatusLine? = null
    private val steps = StepHistory()
    private var stoppedNote = false

    /** 「已发给 DSH：…」 for a moment after 插话, in the line's place. */
    private var steerNote: String? = null
    private var pill: OverlayCapsule? = null
    private var confirmCard: Card? = null
    private var handoffCard: Card? = null
    private var pendingConfirm: Pending<Decision>? = null
    private var pendingHandoff: Pending<HandoffResult>? = null
    @Volatile private var sharedPrompt: PromptPresentation? = null
    private var passThrough = false
    private var captureHidden = false
    private var darkBuilt: Boolean? = null

    // The form: FoldClock decides, the morphs follow.
    private val fold = FoldClock()
    private var ball: OverlayBall? = null

    /** What is on screen: the capsule (or card), or the ball (lags [fold] while a fold or unfold plays). */
    private var ballShown = false
    private val foldTick = Runnable { onFoldTick() }

    /** A narrower line may shrink the capsule now. */
    private val shrinkTick = Runnable { renderPill() }

    // 闲置时不透明度 (the ball's) and 「闲置时自动收起」 (设置 → 手机操控 → 悬浮条), live while showing.
    private val overlayPrefs = OverlayPrefs.prefs(service)
    private var idleOpacity = OverlayPrefs.from(overlayPrefs).idleOpacity
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == OverlayPrefs.K_OPACITY || key == OverlayPrefs.K_AUTO_FOLD) main.post { onPrefsChanged() }
    }

    init {
        fold.setAutoFold(OverlayPrefs.from(overlayPrefs).autoFold, SystemClock.uptimeMillis())
        overlayPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private class Pending<T : Any> {
        private val latch = CountDownLatch(1)
        @Volatile var value: T? = null
            private set

        fun resolve(v: T): Boolean {
            synchronized(this) { if (value != null) return false; value = v }
            latch.countDown()
            return true
        }

        fun await(ms: Long): T? { latch.await(ms, TimeUnit.MILLISECONDS); return value }
    }

    private class Card(val root: FrameLayout, val params: WindowManager.LayoutParams, val tick: Runnable?)

    // ---- UserGate --------------------------------------------------------------------------------

    override fun status(text: String?) {
        main.post {
            statusText = text
            setStopped(false)
            if (text == null) {
                paused = false
                line = null
                steps.clear()
                removePill(animated = true)
            } else {
                // The core's formats in the app language: the prefixes it puts on, taken off here.
                val l = StatusLines(AppStrings.get(service, R.string.phone_waived_status), AppStrings.get(service, R.string.phone_acting)).parse(text)
                line = l
                steps.add(l)
                showPill()
            }
        }
    }

    /** The turn that drove the phone is over: every window goes, pending questions answer STOP. */
    override fun close(ifCurrent: (() -> Unit) -> Unit) {
        val shared = sharedPrompt
        val confirm = pendingConfirm
        val handoff = pendingHandoff
        main.post {
            ifCurrent {
                // A new pre-tool engine prompt can share an inactive task id. Remove only the
                // questions captured by this cleanup, even when the task itself has not started.
                if (sharedPrompt === shared) {
                    shared?.ticket?.stop()
                    if (shared != null) { sharedPrompt = null; dismissConfirm() }
                }
                if (sharedPrompt == null && pendingConfirm === confirm) {
                    confirm?.resolve(Decision.STOP)
                    dismissConfirm()
                }
                if (pendingHandoff === handoff) {
                    handoff?.resolve(HandoffResult.STOP)
                    dismissHandoff()
                }
                statusText = null
                line = null
                steps.clear()
                setStopped(false)
                paused = false
                removePill(animated = true)
            }
        }
    }

    override fun confirm(request: ConfirmRequest): Decision {
        PhoneRegistry.promptGate?.let { return it.confirm(request) }
        if (Looper.myLooper() == Looper.getMainLooper()) { Log.w(TAG, "confirm() on the main thread: denied"); return Decision.DENY }
        val pending = Pending<Decision>()
        val shown = onMain(1_500) { showConfirm(request, pending) } == true
        if (!shown) {
            pending.resolve(Decision.UNAVAILABLE)
            main.post { if (pendingConfirm === pending) dismissConfirm() }
            Log.w(TAG, "confirm overlay could not be shown; falling back to the notification gate")
            return PhoneRegistry.notificationGate?.confirm(request) ?: Decision.DENY
        }
        val decision = try {
            pending.await(request.timeoutMs + 1_500) ?: run { pending.resolve(Decision.DENY); pending.value!! }
        } catch (_: InterruptedException) {
            // The core's stop interrupts the worker: answer STOP and keep the flag for the caller.
            pending.resolve(Decision.STOP)
            Thread.currentThread().interrupt()
            pending.value!!
        }
        main.post { if (pendingConfirm === pending) dismissConfirm() }
        return decision
    }

    override fun handoff(reason: String, timeoutMs: Long): HandoffResult = handoff(reason, timeoutMs, null)

    override fun handoff(reason: String, timeoutMs: Long, cause: HandoffCause?): HandoffResult {
        PhoneRegistry.promptGate?.let { return it.handoff(reason, timeoutMs, cause) }
        if (Looper.myLooper() == Looper.getMainLooper()) return HandoffResult.STOP
        val limit = timeoutMs.coerceIn(5_000, 10 * 60_000)
        val pending = Pending<HandoffResult>()
        val shown = onMain(1_500) { showHandoff(reason, limit, pending, cause) } == true
        if (!shown) {
            pending.resolve(HandoffResult.STOP)
            main.post { if (pendingHandoff === pending) dismissHandoff() }
            return PhoneRegistry.notificationGate?.handoff(reason, limit, cause) ?: HandoffResult.STOP
        }
        val result = try {
            pending.await(limit + 1_500) ?: run { pending.resolve(HandoffResult.TIMEOUT); pending.value!! }
        } catch (_: InterruptedException) {
            pending.resolve(HandoffResult.STOP)
            Thread.currentThread().interrupt()
            pending.value!!
        }
        main.post { if (pendingHandoff === pending) dismissHandoff() }
        return result
    }

    // ---- hooks for the driver ---------------------------------------------------------------------

    /** Makes every overlay window untouchable (true) so an injected stroke reaches the app below. */
    fun setPassThrough(on: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) { applyPassThrough(on); return }
        val changed = onMain(500) { applyPassThrough(on) } == true
        if (on && changed) SystemClock.sleep(80) // input windows update a frame after the layout
    }

    /** Hides the overlay for a full-display screenshot (API 30–33). */
    fun hideForCapture(hide: Boolean) {
        val changed = onMain(500) {
            if (captureHidden == hide) return@onMain false
            captureHidden = hide
            val v = if (hide) View.INVISIBLE else View.VISIBLE
            syncWindows()
            confirmCard?.root?.visibility = v
            handoffCard?.root?.visibility = v
            pill != null || confirmCard != null || handoffCard != null
        } == true
        if (hide && changed) SystemClock.sleep(120)
    }

    /** Screen off, lock, 停止 elsewhere, service going away: answer everything pending with STOP. */
    fun stopAll() {
        val shared = sharedPrompt
        val confirm = pendingConfirm
        val handoff = pendingHandoff
        shared?.ticket?.stop()
        main.post {
            if (sharedPrompt === shared) {
                if (shared != null) { sharedPrompt = null; dismissConfirm() }
            }
            if (sharedPrompt == null && pendingConfirm === confirm) { confirm?.resolve(Decision.STOP); dismissConfirm() }
            if (pendingHandoff === handoff) { handoff?.resolve(HandoffResult.STOP); dismissHandoff() }
            paused = false
            endEditing()
            if (statusText != null) { setStopped(true); renderPill(); clearStoppedLater() }
        }
    }

    /** "已停止" stays briefly; if the core doesn't clear or replace the status, the control (and its KEEP_SCREEN_ON) goes. */
    private fun clearStoppedLater() {
        main.postDelayed({ if (stoppedNote) { statusText = null; line = null; steps.clear(); setStopped(false); paused = false; removePill(animated = true) } }, 4_000)
    }

    /** The 已停止 note: holds the capsule (or the ball) as it is, the card folding back first. */
    private fun setStopped(on: Boolean) {
        stoppedNote = on
        if (on) holdFold(FoldClock.Hold.STOPPED) else releaseFold(FoldClock.Hold.STOPPED)
    }

    /** Debug probe: screen centre of the visible button labelled [label], if any. */
    fun debugButtonCenter(label: String): Pair<Int, Int>? = onMain(1_000) {
        fun find(v: View): View? {
            if ((v is TextView && v.text?.toString() == label) || v.contentDescription?.toString() == label) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val roots = listOfNotNull(confirmCard?.root, handoffCard?.root, pill?.root?.takeIf { it.isShown }, ball?.root?.takeIf { it.isShown })
        roots.firstNotNullOfOrNull { find(it) }?.let { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            (loc[0] + v.width / 2) to (loc[1] + v.height / 2)
        }
    }

    /** Debug probe: puts the control in [form] (ball, capsule, card) as the person's taps would. */
    fun debugForm(form: String) {
        main.post {
            when (form) {
                "ball" -> if (!fold.folded) collapseByHand()
                "capsule" -> if (fold.folded) onBallTapped() else if (fold.expanded) onLineTapped()
                "card" -> if (fold.folded) { onBallTapped(); main.postDelayed({ onLineTapped() }, 700) } else if (!fold.expanded) onLineTapped()
            }
        }
    }

    /** Debug probe: 暂停 / 继续 / 停止 / the steer field, as the keys do. */
    fun debugKey(key: String) {
        main.post {
            when (key) {
                "pause" -> togglePause()
                "stop" -> stopFromPill()
                "steer" -> startSteer()
                "done" -> endEditing()
            }
        }
    }

    /** Debug probe: what the control is showing. */
    fun debugState(): String = onMain(1_000) {
        "form=${fold.form} ballShown=$ballShown shape=${pill?.shape} paused=$paused stopped=$stoppedNote line=${line?.text} " +
            "window=${pill?.params?.let { "${it.width}x${it.height}@${it.y}" }} ball=${ball?.let { "${it.pos}" }} steps=${steps.before(line, 3)}"
    } ?: "timeout"

    fun onConfigurationChanged() {
        main.post {
            val dark = isDark()
            if (darkBuilt != null && darkBuilt != dark) {
                // Rebuild in the new palette; pending questions keep their latches.
                pill?.let { removePill(animated = false); if (statusText != null) showPill() }
            } else {
                pill?.let { p ->
                    placeCapsule(p)
                    ball?.let { b -> if (ballShown) b.move(b.dockPos()) }
                }
                confirmCard?.let { place(it.root, it.params, top = false) }
                handoffCard?.let { place(it.root, it.params, top = true) }
            }
        }
    }

    fun destroy() {
        sharedPrompt?.ticket?.stop()
        pendingConfirm?.resolve(Decision.STOP)
        pendingHandoff?.resolve(HandoffResult.STOP)
        overlayPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        main.post {
            dismissConfirm()
            dismissHandoff()
            removePill(animated = false)
        }
    }

    // ---- capsule ----------------------------------------------------------------------------------

    private fun s(id: Int, vararg args: Any): String = AppStrings.get(service, id, *args)

    private fun unlocks(): PhoneUnlocks = debugUnlocks ?: PhoneRegistry.unlocks()

    /** A confirm (or a shared prompt) is up: the line points at it. */
    private fun waiting() = pendingConfirm != null || sharedPrompt != null

    private fun showPill() {
        if (pill == null) {
            darkBuilt = isDark()
            val p = buildPill()
            if (!add(p.root, p.params)) return
            pill = p
            ballShown = false
            fold.start(now())
            syncWindows()
            renderPill(animated = false)
            enter(p.root)
            scheduleFold()
            return
        }
        renderPill()
    }

    /** What the capsule, the card and the ball say now; [animated] cross-fades what changed. */
    private fun renderPill(animated: Boolean = true) {
        val p = pill ?: return
        val base = line ?: return
        val u = unlocks()
        val look = when {
            stoppedNote -> MarkLook.STOPPED
            waiting() -> MarkLook.WAITING
            paused -> MarkLook.PAUSED
            else -> MarkLook.RUNNING
        }
        val text = when {
            stoppedNote -> s(R.string.overlay_stopped)
            waiting() -> s(R.string.overlay_waiting)
            paused -> s(R.string.overlay_paused_short)
            else -> steerNote ?: base.text
        }
        val sep = s(R.string.overlay_meta_separator)
        val app = debugApp ?: PhoneControl.peek()?.liveTask()?.app
        val meta = listOfNotNull(app, if (base.waived) s(R.string.overlay_waived) else null).joinToString(sep).ifEmpty { null }
        val state = CapsuleState(
            line = text,
            look = look,
            paused = paused,
            stopped = stoppedNote,
            expandable = !stoppedNote && !waiting(),
            unlocked = u.any,
            unlockedLine = if (u.any) unlockedLine(u) else null,
            meta = meta,
            steps = steps.before(base, StepHistory.CAPACITY),
        )
        p.render(state, animated && p.root.isShown && !ballShown, now())
        main.removeCallbacks(shrinkTick)
        p.shrinkAt?.let { main.postAtTime(shrinkTick, it + 1) }
        ball?.setLook(
            look,
            s(
                when (look) {
                    MarkLook.STOPPED -> R.string.overlay_ball_stopped
                    MarkLook.WAITING -> R.string.overlay_ball_waiting
                    MarkLook.PAUSED -> R.string.overlay_ball_paused
                    MarkLook.RUNNING -> R.string.overlay_ball_running
                },
            ),
            badge = u.any && !stoppedNote,
            stopLabel = if (stoppedNote) null else s(R.string.overlay_stop_description),
        )
    }

    /** 「已解除限制」 in bold over what is lifted (「系统设置、密码和验证码」), a size smaller. */
    private fun unlockedLine(u: PhoneUnlocks): CharSequence {
        val names = listOfNotNull(
            if (u.systemPages) s(R.string.overlay_unlock_system) else null,
            if (u.secretFields) s(R.string.overlay_unlock_secrets) else null,
            if (u.ownApp) s(R.string.overlay_unlock_own_app) else null,
        ).joinToString(s(R.string.overlay_list_separator))
        val title = s(R.string.overlay_unlocked)
        return SpannableStringBuilder(title).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append('\n')
            val at = length
            append(names)
            setSpan(RelativeSizeSpan(12f / 13f), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Takes the control away; [animated]: it fades first (a task that ended, not a rebuild). */
    private fun removePill(animated: Boolean) {
        val p = pill ?: return
        endEditing()
        main.removeCallbacks(foldTick)
        main.removeCallbacks(shrinkTick)
        fold.reset()
        val b = ball
        pill = null
        ball = null
        ballShown = false
        steerNote = null
        p.cancelMorph()
        val roots = listOfNotNull(p.root.takeIf { it.isShown }, b?.root?.takeIf { it.isShown })
        if (!animated || !animationsOn() || roots.isEmpty()) {
            remove(p.root)
            b?.detach()
            return
        }
        b?.cancelGlide()
        var left = roots.size
        for (r in roots) {
            r.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(180).setInterpolator(OverlayMotion.emphasized).withEndAction {
                if (--left == 0) { remove(p.root); b?.detach() }
            }.start()
        }
    }

    private fun buildPill(): OverlayCapsule {
        val c = colors()
        val strings = OverlayCapsule.Strings(
            fold = s(R.string.overlay_fold), expand = s(R.string.overlay_expand), collapse = s(R.string.overlay_collapse),
            pause = s(R.string.overlay_pause_description), resume = s(R.string.overlay_resume_description),
            stop = s(R.string.overlay_stop_description), steerHint = s(R.string.overlay_steer_hint), send = s(R.string.overlay_send),
            unlocksDescription = s(R.string.overlay_unlocked_description), review = s(R.string.overlay_unlocks_review),
            foldRow = s(R.string.overlay_fold_row), holdHint = s(R.string.overlay_hold_hint),
        )
        lateinit var capsule: OverlayCapsule
        val actions = object : OverlayCapsule.Actions {
            override fun touched() = onPillTouched()
            override fun mark() = collapseByHand()
            override fun line() = onLineTapped()
            override fun pause() = togglePause()
            override fun stop() = stopFromPill()
            override fun unlocks() = openUnlocks()
            override fun steer() = startSteer()
            override fun send() = sendInterjection()
            override fun inputDone() = endEditing()
        }
        capsule = OverlayCapsule(service, c, strings, actions) { update(capsule.root, capsule.params) }
        capsule.params = params(top = true, keepScreenOn = true)
        placeCapsule(capsule)
        return capsule
    }

    /** Centred at the top, [CapsuleMetrics.TOP_GAP_DP] under the status bar; sizes for this screen. */
    private fun placeCapsule(p: OverlayCapsule) {
        val a = safeArea()
        p.params.y = a.top + dp(CapsuleMetrics.TOP_GAP_DP) - p.padTop
        p.place(a.screenW, a.screenH)
        update(p.root, p.params)
    }

    /** The 「已解除限制」 row: 设置 › 手机操控 › 解除限制, through the app's own page link. */
    private fun openUnlocks() {
        val intent = Intent(service, com.deepseekharness.app.ui.MainActivity::class.java)
            .putExtra("open_phone_unlocks", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { service.startActivity(intent) } catch (e: Exception) { Log.w(TAG, "unlocks page: ${e.javaClass.simpleName}") }
    }

    private fun togglePause() {
        if (stoppedNote) return
        paused = !paused
        renderPill()
        PhoneRegistry.controlListener?.onPauseChanged(paused)
        announce(s(if (paused) R.string.overlay_paused_short else R.string.overlay_resume_description))
    }

    /** 停止 on the capsule or the card, or the ball held: everything pending answers STOP. */
    private fun stopFromPill() {
        if (stoppedNote) return
        sharedPrompt?.ticket?.stop()
        pendingConfirm?.resolve(Decision.STOP)
        pendingHandoff?.resolve(HandoffResult.STOP)
        dismissConfirm()
        dismissHandoff()
        paused = false
        endEditing()
        setStopped(true)
        renderPill()
        clearStoppedLater()
        announce(s(R.string.overlay_stopped))
        // l10n:keep agent stop reason
        PhoneRegistry.controlListener?.onStopRequested("你在悬浮条上点了停止")
    }

    private fun announce(text: String) {
        val v = if (ballShown) ball?.root else pill?.root
        if (a11y.isEnabled) v?.announceForAccessibility(text)
    }

    /** The line: the capsule opens into the card, the card closes. */
    private fun onLineTapped() {
        val p = pill ?: return
        if (stoppedNote || ballShown) return
        if (fold.expanded) {
            endEditing()
            if (fold.collapse(now())) p.collapse(animationsOn())
        } else if (fold.expand(now())) {
            renderPill(animated = false)
            p.expand(animationsOn())
        }
        scheduleFold()
    }

    /** The steer field in the card: the window takes the keyboard until the text goes or is sent. */
    private fun startSteer() {
        val p = pill ?: return
        if (stoppedNote || !fold.expanded || editing) return
        editing = true
        holdFold(FoldClock.Hold.EDITING)
        p.params.flags = p.params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        update(p.root, p.params)
        p.startEditing()
        p.input.post { if (editing) ime.showSoftInput(p.input, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun endEditing() {
        val p = pill
        if (p != null && (editing || p.editing)) {
            ime.hideSoftInputFromWindow(p.input.windowToken, 0)
            p.stopEditing()
            if (p.params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) {
                p.params.flags = p.params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                update(p.root, p.params)
            }
        }
        editing = false
        releaseFold(FoldClock.Hold.EDITING)
    }

    private fun sendInterjection() {
        val p = pill ?: return
        val text = p.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        p.input.setText("")
        endEditing()
        PhoneRegistry.controlListener?.onInterject(text)
        val note = s(R.string.overlay_steer_sent, text)
        steerNote = note
        // Back to the capsule: the note shows where the line is.
        if (fold.collapse(now())) p.collapse(animationsOn())
        renderPill()
        scheduleFold()
        main.postDelayed({ if (pill === p && steerNote == note) { steerNote = null; renderPill() } }, 2_500)
    }

    // ---- confirm --------------------------------------------------------------------------------

    /** Called only by the shared main-thread coordinator; no blocking or implicit fallback. */
    override fun showPrompt(presentation: PromptPresentation): (() -> Unit)? {
        val p = presentation
        if (Looper.myLooper() != Looper.getMainLooper() || !p.live || confirmCard != null || handoffCard != null) return null
        val root = FrameLayout(service).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(PhonePromptUi.card(service, p), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val layout = params(top = false, keepScreenOn = true)
        place(root, layout, top = false)
        if (!p.live || !add(root, layout)) return null
        if (!p.markShown()) { remove(root); return null }
        sharedPrompt = p
        confirmCard = Card(root, layout, null)
        holdFold(FoldClock.Hold.CONFIRM)
        renderPill()
        return {
            if (sharedPrompt === p) {
                sharedPrompt = null
                dismissConfirm()
            }
            p.retire()
        }
    }

    private fun showConfirm(request: ConfirmRequest, pending: Pending<Decision>): Boolean {
        if (sharedPrompt != null || pending.value != null) return false
        pendingConfirm?.resolve(Decision.DENY)
        dismissConfirm()
        val c = colors()
        darkBuilt = isDark()
        val deadline = SystemClock.uptimeMillis() + request.timeoutMs
        val root = FrameLayout(service).apply { setPadding(dp(6), dp(8), dp(6), dp(6)); clipToPadding = false }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(c, dp(24).toFloat())
            elevation = dp(8).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        val head = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        val app = request.appLabel?.takeIf { it.isNotBlank() }
        head.addView(TextView(service).apply {
            text = if (request.firstInApp && app != null) AppStrings.get(service, R.string.overlay_first_app, app) else AppStrings.get(service, R.string.overlay_action_title)
            textSize = 12f; setTextColor(c.accent); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val countdown = TextView(service).apply { textSize = 12f; setTextColor(c.muted) }
        head.addView(countdown)
        card.addView(head)
        val body = TextView(service).apply {
            text = request.sentence
            textSize = 16f; setTextColor(c.ink); setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8), 0, dp(4))
        }
        card.addView(ScrollView(service).apply { addView(body); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(TextView(service).apply {
            text = if (request.offerTaskWaiver) {
                AppStrings.get(service, R.string.overlay_waiver_hint)
            } else {
                AppStrings.get(service, R.string.overlay_timeout_hint)
            }
            textSize = 12f; setTextColor(c.faint); setLineSpacing(0f, 1.2f); setPadding(0, 0, 0, dp(10))
        })

        val armedAt = SystemClock.uptimeMillis() + ARM_MS
        val guarded = mutableListOf<GuardedButton>()
        fun decide(d: Decision) { if (pending.resolve(d)) main.post { if (pendingConfirm === pending) dismissConfirm() } }
        // 本任务免确认: the main choice waives the rest of the task. Otherwise, on the first action
        // in an app, the main choice allows that app for the rest of the task so later steps there
        // don't ask again. "Only this step" stays available as the quieter option either way.
        val wide = request.offerTaskWaiver || request.firstInApp
        if (request.offerTaskWaiver) {
            val all4task = GuardedButton(service, armedAt).apply {
                text = AppStrings.get(service, R.string.overlay_allow_remaining)
                style(this, c.accent, c.onAccent, c)
                setOnClickListener { decide(Decision.ALLOW_ALL_FOR_TASK) }
            }
            guarded += all4task
            card.addView(all4task, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(8) })
        } else if (request.firstInApp) {
            val app4task = GuardedButton(service, armedAt).apply {
                text = if (app != null) AppStrings.get(service, R.string.overlay_allow_app_task, app) else AppStrings.get(service, R.string.overlay_allow_this_app_task)
                style(this, c.accent, c.onAccent, c)
                setOnClickListener { decide(Decision.ALLOW_APP_FOR_TASK) }
            }
            guarded += app4task
            card.addView(app4task, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(8) })
        }
        val row = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(textButton(AppStrings.get(service, R.string.overlay_stop), c.danger, c) { decide(Decision.STOP) })
        row.addView(View(service), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(textButton(AppStrings.get(service, R.string.overlay_decline), c.ink, c) { decide(Decision.DENY) }.apply { background = outlined(c) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)).apply { marginEnd = dp(8) })
        val allow = GuardedButton(service, armedAt).apply {
            text = if (wide) AppStrings.get(service, R.string.overlay_allow_step) else AppStrings.get(service, R.string.overlay_allow_once)
            if (wide) style(this, c.accentSoft, c.onAccentSoft, c) else style(this, c.accent, c.onAccent, c)
            setOnClickListener { decide(Decision.ALLOW_ONCE) }
        }
        guarded += allow
        row.addView(allow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)))
        card.addView(row)
        root.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        guarded.forEach { it.alpha = 0.5f }
        main.postDelayed({ guarded.forEach { it.animate().alpha(1f).setDuration(120).start() } }, ARM_MS)

        val tick = object : Runnable {
            override fun run() {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) { decide(Decision.DENY); return }
                countdown.text = AppStrings.plural(service, R.plurals.overlay_seconds, ((left + 999) / 1000).toInt(), (left + 999) / 1000)
                main.postDelayed(this, 1_000)
            }
        }
        val params = params(top = false, keepScreenOn = true)
        place(root, params, top = false)
        if (!add(root, params)) return false
        if (pending.value != null) { remove(root); return false }
        confirmCard = Card(root, params, tick)
        pendingConfirm = pending
        tick.run()
        enter(root)
        holdFold(FoldClock.Hold.CONFIRM)
        renderPill()
        return true
    }

    private fun dismissConfirm() {
        val card = confirmCard ?: return
        card.tick?.let(main::removeCallbacks)
        remove(card.root)
        confirmCard = null
        pendingConfirm = null
        releaseFold(FoldClock.Hold.CONFIRM)
        renderPill()
    }

    // ---- handoff --------------------------------------------------------------------------------

    private fun showHandoff(reason: String, timeoutMs: Long, pending: Pending<HandoffResult>, cause: HandoffCause?): Boolean {
        if (sharedPrompt != null || pending.value != null) return false
        pendingHandoff?.resolve(HandoffResult.STOP)
        dismissHandoff()
        val c = colors()
        darkBuilt = isDark()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val root = FrameLayout(service).apply { setPadding(dp(6), dp(4), dp(6), dp(8)); clipToPadding = false }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(c, dp(22).toFloat())
            elevation = dp(8).toFloat()
            setPadding(dp(18), dp(14), dp(14), dp(10))
        }
        val head = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(service).apply {
            text = cause?.let { PhonePromptUi.handoffCause(service, it) } ?: AppStrings.get(service, R.string.overlay_handoff_title)
            textSize = 12f; setTextColor(c.accent); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val countdown = TextView(service).apply { textSize = 12f; setTextColor(c.muted) }
        head.addView(countdown)
        card.addView(head)
        card.addView(TextView(service).apply {
            text = reason; textSize = 15f; setTextColor(c.ink); maxLines = 4; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.25f); setPadding(0, dp(6), 0, dp(2))
        })
        card.addView(TextView(service).apply {
            text = AppStrings.get(service, R.string.overlay_handoff_hint); textSize = 12f; setTextColor(c.faint); setPadding(0, 0, 0, dp(6))
        })
        fun finish(r: HandoffResult) { if (pending.resolve(r)) main.post { if (pendingHandoff === pending) dismissHandoff() } }
        val row = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(textButton(AppStrings.get(service, R.string.overlay_stop), c.danger, c) { finish(HandoffResult.STOP) })
        row.addView(View(service), LinearLayout.LayoutParams(0, 1, 1f))
        val armedAt = SystemClock.uptimeMillis() + ARM_MS
        val go = GuardedButton(service, armedAt).apply {
            text = AppStrings.get(service, R.string.overlay_continue)
            style(this, c.accent, c.onAccent, c)
            setOnClickListener { finish(HandoffResult.CONTINUE) }
        }
        row.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        card.addView(row)
        root.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val tick = object : Runnable {
            override fun run() {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) { finish(HandoffResult.TIMEOUT); return }
                val s = (left + 999) / 1000
                countdown.text = if (s >= 60) AppStrings.get(service, R.string.overlay_time_remaining, "${s / 60}:${"%02d".format(s % 60)}") else AppStrings.plural(service, R.plurals.overlay_seconds_remaining, s.toInt(), s)
                main.postDelayed(this, 1_000)
            }
        }
        val params = params(top = true, keepScreenOn = true)
        place(root, params, top = true)
        if (!add(root, params)) return false
        if (pending.value != null) { remove(root); return false }
        handoffCard = Card(root, params, tick)
        pendingHandoff = pending
        // The card says it all: capsule and ball step aside, and the capsule comes back after.
        holdFold(FoldClock.Hold.HANDOFF)
        syncWindows()
        tick.run()
        enter(root)
        return true
    }

    private fun dismissHandoff() {
        val card = handoffCard ?: return
        card.tick?.let(main::removeCallbacks)
        remove(card.root)
        handoffCard = null
        pendingHandoff = null
        syncWindows()
        releaseFold(FoldClock.Hold.HANDOFF)
    }

    // ---- guarded buttons ------------------------------------------------------------------------

    /**
     * A confirm button that only real fingers can press:
     *  - not in its first [ARM_MS] (a tap already in flight when the card appeared);
     *  - not while this service is injecting a gesture (InjectGuard);
     *  - not when the touch came through another window on top (tapjacking, FLAG_WINDOW_IS_OBSCURED);
     *  - not when it looks like an accessibility service's stroke: a virtual device (id ≤ 0) and
     *    either FLAG_IS_ACCESSIBILITY_EVENT (0x800) or tool type UNKNOWN — unless TalkBack-style
     *    touch exploration is on, whose double-tap takes that same path;
     *  - ACTION_CLICK from any accessibility service is refused unless touch exploration is on.
     * And the Actuator makes the overlay untouchable for every stroke it injects.
     * Measured on the API 35 emulator (debug probe): our dispatchGesture tap = flags 0x800,
     * device -1, tool UNKNOWN; a hardware-touchscreen tap = flags 0x800 (!), device 12, tool FINGER;
     * shell `input tap` = flags 0, device -1, tool FINGER. So 0x800 alone would lock real fingers
     * out; the device id is what separates them.
     */
    private inner class GuardedButton(context: Context, private val armedAt: Long) : TextView(context) {
        private var rejected = false

        init { filterTouchesWhenObscured = true; isClickable = true; isFocusable = true; gravity = Gravity.CENTER }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val why = rejectReason(event)
                rejected = why != null
                lastGuardVerdict = "${why ?: "accepted"} flags=0x${Integer.toHexString(event.flags)} device=${event.deviceId} source=0x${Integer.toHexString(event.source)} tool=${event.getToolType(0)}"
                Log.i(TAG, "guarded '$text': $lastGuardVerdict")
            }
            if (rejected) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) isPressed = false
                return true
            }
            return super.dispatchTouchEvent(event)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if ((action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) && !a11y.isTouchExplorationEnabled) {
                lastGuardVerdict = "accessibility-action"
                Log.i(TAG, "guarded '$text': refused accessibility click")
                return false
            }
            return super.performAccessibilityAction(action, arguments)
        }

        private fun rejectReason(event: MotionEvent): String? = when {
            SystemClock.uptimeMillis() < armedAt -> "too-early"
            InjectGuard.active() && !debugIgnoreInjectGuard -> "injecting"
            event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 -> "obscured"
            event.deviceId <= 0 && (event.flags and FLAG_IS_ACCESSIBILITY_EVENT != 0 || event.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN) &&
                !a11y.isTouchExplorationEnabled -> "accessibility-injected"
            else -> null
        }
    }

    // ---- forms -----------------------------------------------------------------------------------
    //
    // Folding: the surface narrows to the circle at its end on the ball's side, the row fading
    // first; the ball takes its place and glides to its dock. Unfolding: the ball glides back to
    // that end and the capsule grows out of it. Capsule ↔ card: the same surface grows down and
    // out, the body fading in behind (OverlayCapsule). One window shows at a time, except a few
    // frames of overlap at the swap (both are the same circle there), so neither ever blinks.

    private fun now() = SystemClock.uptimeMillis()

    private fun scheduleFold() {
        main.removeCallbacks(foldTick)
        if (debugFreeze) return
        val at = fold.nextAt ?: return
        main.postAtTime(foldTick, at)
    }

    private fun onFoldTick() {
        if (debugFreeze) return
        when (fold.tick(now())) {
            FoldClock.Tick.FOLD -> foldPill(animationsOn())
            FoldClock.Tick.COLLAPSE -> pill?.collapse(animationsOn())
            FoldClock.Tick.DIM -> applyIdle(animated = true)
            FoldClock.Tick.NONE -> Unit
        }
        scheduleFold()
    }

    private fun onPillTouched() {
        // A touch on a capsule that is still shrinking into the ball opens it again.
        if (fold.folded && !ballShown) {
            fold.unfold(now())
            pill?.cancelMorph()
        } else fold.touch(now())
        scheduleFold()
    }

    private fun onBallTouched() {
        // A finger stops an unfold's glide: the capsule it had brought up (transparent) goes again.
        syncWindows()
        fold.touch(now())
        applyIdle(animated = true)
        scheduleFold()
    }

    private fun onBallTapped() {
        if (fold.unfold(now())) unfoldPill(animationsOn())
        applyIdle(animated = true)
        scheduleFold()
    }

    /** The mark (or the card's 收成小球): folded until the ball is tapped, for the rest of this task. */
    private fun collapseByHand() {
        endEditing()
        if (fold.foldByHand(now())) foldPill(animationsOn())
        scheduleFold()
    }

    private fun holdFold(hold: FoldClock.Hold) {
        // A handoff hides both anyway: change form without a show.
        if (fold.hold(hold, now())) syncForm(animationsOn() && hold != FoldClock.Hold.HANDOFF)
        applyIdle(animated = true)
        scheduleFold()
    }

    private fun releaseFold(hold: FoldClock.Hold) {
        fold.release(hold, now())
        scheduleFold()
    }

    /** Brings what is on screen to the form [fold] says. */
    private fun syncForm(animated: Boolean) {
        val p = pill ?: return
        when (fold.form) {
            FoldClock.Form.BALL -> if (!ballShown) foldPill(animated)
            FoldClock.Form.CAPSULE -> when {
                ballShown -> unfoldPill(animated)
                p.shape == OverlayCapsule.Shape.CARD -> { endEditing(); p.collapse(animated) }
            }
            FoldClock.Form.CARD -> if (!ballShown && p.shape != OverlayCapsule.Shape.CARD) p.expand(animated)
        }
    }

    private fun onPrefsChanged() {
        val p = OverlayPrefs.from(overlayPrefs)
        idleOpacity = p.idleOpacity
        fold.setAutoFold(p.autoFold, now())
        // A new 不透明度 shows at once on a faded ball.
        applyIdle(animated = false)
        scheduleFold()
    }

    /**
     * The ball fades, all of it alike, to the idle opacity, or comes back to full: slowly going
     * idle, quickly on a touch. The capsule and the card never fade — they carry the line, on a
     * solid surface; what wants them out of the way folds them.
     */
    private fun applyIdle(animated: Boolean) {
        val b = ball ?: return
        val target = if (fold.folded && fold.dimmed) idleOpacity else 1f
        b.setIdle(target, animated, if (target < 1f) DIM_FADE_MS else UNDIM_FADE_MS)
    }

    /** The ball's window for this control, added (hidden) on first use; null when the system refused it. */
    private fun ensureBall(): OverlayBall? {
        ball?.let { return it }
        val b = OverlayBall(service, wm, ::safeArea, colors(), ::onBallTapped, ::stopFromPill, ::onBallTouched)
        if (!b.attach(s(R.string.overlay_window_title), passThrough, b.dockPos())) return null
        ball = b
        renderPill(animated = false)
        return b
    }

    /** The ball window over the surface's circle at ([circle]) in the capsule window. */
    private fun ballOver(p: OverlayCapsule, b: OverlayBall, circle: Pair<Int, Int>): WindowPos = BallGeometry.ballOver(
        windowX = BallGeometry.centredX(safeArea().screenW, p.params.width), windowY = p.params.y,
        padX = p.padX, padTop = p.padTop, circleLeft = circle.first, circleTop = circle.second, pad = b.pad,
    )

    private fun foldPill(animated: Boolean) {
        val p = pill ?: return
        if (ballShown) return
        endEditing()
        val b = ensureBall()
        if (b == null) {
            // No second window: the capsule stays as it was.
            fold.unfold(now())
            scheduleFold()
            return
        }
        // Folding is a change worth seeing: at full from here, fading again later.
        applyIdle(animated = false)
        val shown = animated && p.root.isShown && p.surface.width > 0
        if (!shown) {
            p.cancelMorph()
            p.settle(OverlayCapsule.Shape.CAPSULE)
            b.move(b.dockPos())
            b.fadeMark(1f, animated = false)
            ballShown = true
            syncWindows()
            renderPill(animated = false)
            return
        }
        val side = b.dock.side
        b.move(ballOver(p, b, p.endCircle(side)))
        p.foldTo(side, animated = true) {
            ballShown = true
            b.fadeMark(0f, animated = false)
            showWindow(b.root)
            b.fadeMark(1f, animated = true)
            // The capsule goes (and gets its plain layout back) once the ball is surely drawn over it.
            b.root.postDelayed({ if (pill === p && ballShown) { syncWindows(); p.settle(OverlayCapsule.Shape.CAPSULE) } }, SWAP_MS)
            renderPill(animated = false)
            b.glideTo(b.dockPos(), animated = true)
        }
    }

    private fun unfoldPill(animated: Boolean) {
        val p = pill ?: return
        val b = ball
        if (b == null || !ballShown) {
            ballShown = false
            syncWindows()
            renderPill()
            return
        }
        if (!animated || captureHidden || !b.root.isShown) {
            p.settle(OverlayCapsule.Shape.CAPSULE)
            b.move(b.dockPos())
            ballShown = false
            syncWindows()
            renderPill(animated = false)
            return
        }
        // Only the ball shows from here (a fold may have just ended with both up).
        syncWindows()
        val side = b.dock.side
        // The capsule window comes up now, transparent, as the circle the ball is heading for:
        // drawn before the swap, so the morph starts on its first frame instead of popping in late.
        renderPill(animated = false)
        p.prepareUnfold(side)
        p.root.alpha = 0f
        p.root.visibility = if (captureHidden) View.INVISIBLE else View.VISIBLE
        // The ball goes back to the capsule's end first, its mark fading.
        b.fadeMark(0f, animated = true, durationMs = UNFOLD_GLIDE_MS)
        b.glideTo(ballOver(p, b, p.capsuleEndCircle(side)), animated = true, durationMs = UNFOLD_GLIDE_MS) {
            if (pill !== p || !ballShown) return@glideTo
            ballShown = false
            showWindow(p.root)
            p.unfoldFrom(side, animated = true) {}
            // The ball goes once the capsule is surely drawn over it, back to its dock for next time.
            b.root.postDelayed({
                if (pill === p && !ballShown) { syncWindows(); b.move(b.dockPos()); b.fadeMark(1f, animated = false) }
            }, SWAP_MS)
        }
    }

    /**
     * Capsule and ball visibility from the state: the handoff card hides both, a screenshot makes
     * them invisible for its moment, otherwise the one [ballShown] says. GONE windows take no
     * touches, so the hidden one is never in the way.
     */
    private fun syncWindows() {
        val p = pill ?: return
        val handoff = handoffCard != null
        val shown = if (captureHidden) View.INVISIBLE else View.VISIBLE
        p.root.visibility = if (handoff || ballShown) View.GONE else shown
        ball?.root?.visibility = if (handoff || !ballShown) View.GONE else shown
    }

    private fun showWindow(root: View) {
        root.alpha = 1f
        root.visibility = if (captureHidden) View.INVISIBLE else View.VISIBLE
    }

    /** The screen and its bars and cutout, in px (the ball's dock is kept clear of them). */
    private fun safeArea(): SafeArea {
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return SafeArea(m.bounds.width(), m.bounds.height(), i.left, i.top, i.right, i.bottom)
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        val res = Resources.getSystem()
        fun dimen(name: String) = res.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let(res::getDimensionPixelSize) ?: 0
        val status = dimen("status_bar_height")
        val nav = dimen("navigation_bar_height")
        return when (display.rotation) {
            Surface.ROTATION_90 -> SafeArea(dm.widthPixels, dm.heightPixels, 0, status, nav, 0)
            Surface.ROTATION_270 -> SafeArea(dm.widthPixels, dm.heightPixels, nav, status, 0, 0)
            else -> SafeArea(dm.widthPixels, dm.heightPixels, 0, status, 0, nav)
        }
    }

    // ---- window plumbing ------------------------------------------------------------------------

    private fun params(top: Boolean, keepScreenOn: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0) or
            (if (passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        title = s(R.string.overlay_window_title)
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    /** The confirm and handoff cards: nearly the screen's width, at the top or the bottom. */
    private fun place(root: View, params: WindowManager.LayoutParams, top: Boolean) {
        val (screenW, insetTop, insetBottom) = metrics()
        params.width = minOf(screenW - dp(12), dp(if (top) 492 else 472))
        params.y = if (top) insetTop + dp(2) else insetBottom + dp(6)
        if (root.isAttachedToWindow) update(root, params)
    }

    private fun applyPassThrough(on: Boolean): Boolean {
        if (passThrough == on) return false
        passThrough = on
        var any = false
        for ((root, params) in windows()) {
            params.flags = if (on) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            update(root, params)
            any = true
        }
        return any
    }

    private fun windows(): List<Pair<View, WindowManager.LayoutParams>> = listOfNotNull(
        pill?.let { it.root to it.params }, ball?.let { it.root to it.params },
        confirmCard?.let { it.root to it.params }, handoffCard?.let { it.root to it.params },
    )

    private fun add(view: View, params: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, params); true
    } catch (e: Exception) { Log.w(TAG, "overlay addView failed", e); false }

    private fun update(view: View, params: WindowManager.LayoutParams) {
        try { if (view.isAttachedToWindow) wm.updateViewLayout(view, params) } catch (e: Exception) { Log.w(TAG, "overlay update failed", e) }
    }

    private fun remove(view: View) {
        try { wm.removeView(view) } catch (_: Exception) {}
    }

    private fun enter(view: View) {
        if (!animationsOn()) return
        view.alpha = 0f
        view.translationY = -dp(6).toFloat()
        view.scaleX = 0.96f
        view.scaleY = 0.96f
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OverlayMotion.emphasized).start()
    }

    /** Runs [block] on the main thread and waits for it; null on timeout. */
    private fun <T> onMain(timeoutMs: Long, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val call = MainThreadCall { try { block() } catch (e: Exception) { Log.w(TAG, "overlay main-thread call failed", e); null } }
        main.post { call.run() }
        return call.await(timeoutMs)
    }

    private fun metrics(): Triple<Int, Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val insets = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Triple(m.bounds.width(), insets.top, insets.bottom)
        }
        val res = Resources.getSystem()
        fun dimen(name: String) = res.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let(res::getDimensionPixelSize) ?: 0
        @Suppress("DEPRECATION")
        val width = wm.defaultDisplay.width
        return Triple(width, dimen("status_bar_height"), dimen("navigation_bar_height"))
    }

    // ---- look -----------------------------------------------------------------------------------

    private fun isDark() = debugDark
        ?: (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    private fun colors() = OverlayPalette.resolve(service, isDark())

    /** The confirm and handoff cards' surface: the control's, as a drawable. */
    private fun surface(c: OverlayPalette, radius: Float) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c.surfaceTop, c.surfaceBottom)).apply {
        cornerRadius = radius
        setStroke(1, c.hairline)
    }

    private fun outlined(c: OverlayPalette) = RippleDrawable(ColorStateList.valueOf(c.ripple), GradientDrawable().apply {
        cornerRadius = dp(14).toFloat(); setStroke(dp(1), c.outline); setColor(Color.TRANSPARENT)
    }, null)

    private fun style(button: TextView, fill: Int, ink: Int, c: OverlayPalette) {
        button.textSize = 15f
        button.setTextColor(ink)
        button.typeface = Typeface.DEFAULT_BOLD
        button.setPadding(dp(18), 0, dp(18), 0)
        button.background = RippleDrawable(ColorStateList.valueOf(c.ripple), GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(fill) }, null)
    }

    private fun textButton(label: String, ink: Int, c: OverlayPalette, action: () -> Unit) = TextView(service).apply {
        text = label
        textSize = 14f
        setTextColor(ink)
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        minimumWidth = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        background = RippleDrawable(ColorStateList.valueOf(c.ripple), null, GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) })
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun animationsOn() = OverlayMotion.on()

    private fun dp(v: Int) = service.dp(v)

    companion object {
        private const val TAG = "DshA11y"
        private const val ARM_MS = 600L

        /** The app's page link for 解除限制 (lib/ui/settings/index/spots.dart). */
        private const val UNLOCKS_LINK = "dshgo://open/phone-unlocks"

        /** The ball's way back to the capsule on a tap: quicker than the way out. */
        private const val UNFOLD_GLIDE_MS = 220L

        /** Frames both windows show at a swap, so the one going is surely covered (≈ 3 frames). */
        private const val SWAP_MS = 48L

        /** Going idle is slow and quiet; a touch brings it back at once. */
        private const val DIM_FADE_MS = 400L
        private const val UNDIM_FADE_MS = 120L

        /** MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT (hidden, API 31+). Not decisive alone, see GuardedButton. */
        private const val FLAG_IS_ACCESSIBILITY_EVENT = 0x800
    }
}
