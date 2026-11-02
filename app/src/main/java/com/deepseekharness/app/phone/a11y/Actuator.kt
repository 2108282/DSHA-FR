// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's permission. See THIRD_PARTY_NOTICES.md.
// Local changes: blocking calls with timeouts; clickable-ancestor lookup before the gesture
// fallback; directional scrolls; IME enter; injected gestures pass through the overlay and are
// recorded in InjectGuard so its confirm buttons can refuse them.

package com.deepseekharness.app.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.deepseekharness.app.phone.PhoneAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** When this service last injected input; the overlay's guarded buttons ignore touches inside it. */
internal object InjectGuard {
    @Volatile var until = 0L
        private set

    fun mark(durationMs: Long) { until = maxOf(until, SystemClock.uptimeMillis() + durationMs + 400) }

    fun active() = SystemClock.uptimeMillis() < until
}

internal class Actuator(private val service: AccessibilityService, private val overlay: ControlOverlay) {
    private val gestureThread = HandlerThread("dsh-a11y-gesture").apply { start() }
    private val gestureHandler = Handler(gestureThread.looper)

    enum class Via { NODE, ANCESTOR, GESTURE }

    class Outcome(val accepted: Boolean, val via: Via?)

    fun close() { gestureThread.quitSafely() }

    /** ACTION_CLICK / ACTION_LONG_CLICK on the node or its nearest clickable ancestor; a tap gesture last. */
    fun click(node: AccessibilityNodeInfo, bounds: Box, screen: Box, long: Boolean): Outcome {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        fun able(n: AccessibilityNodeInfo) = n.isEnabled && if (long) n.isLongClickable else n.isClickable
        if (able(node) && node.performAction(action)) return Outcome(true, Via.NODE)
        // A text inside a clickable row: a finger there would hit the row.
        val rect = Rect()
        var parent: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            parent.getBoundsInScreen(rect)
            if (able(parent) && rect.contains(bounds.centerX, bounds.centerY)) {
                if (parent.performAction(action)) return Outcome(true, Via.ANCESTOR)
                break
            }
            parent = parent.parent
            depth++
        }
        val point = visiblePoint(bounds, screen) ?: return Outcome(false, null)
        val ok = tap(point.first, point.second, long)
        return Outcome(ok, if (ok) Via.GESTURE else null)
    }

    /** A point of [bounds] that is on screen, preferring the centre. */
    private fun visiblePoint(bounds: Box, screen: Box): Pair<Int, Int>? {
        val clipped = bounds.intersect(screen)
        if (clipped.isEmpty) return null
        return clipped.centerX to clipped.centerY
    }

    fun tap(x: Int, y: Int, long: Boolean = false): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = if (long) ViewConfiguration.getLongPressTimeout().toLong() + 300 else 60L
        return gesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), duration)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 350): Boolean {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return gesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(), durationMs)
    }

    /**
     * Dispatches and waits for the gesture. The overlay is made untouchable first so the
     * stroke reaches the app underneath, never the overlay; InjectGuard covers the
     * time it is in flight.
     */
    fun gesture(description: GestureDescription, durationMs: Long): Boolean {
        overlay.setPassThrough(true)
        try {
            InjectGuard.mark(durationMs)
            val latch = CountDownLatch(1)
            var completed = false
            val sent = service.dispatchGesture(description, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { completed = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, gestureHandler)
            if (!sent) return false
            latch.await(durationMs + 2_000, TimeUnit.MILLISECONDS)
            return completed
        } finally {
            InjectGuard.mark(0)
            overlay.setPassThrough(false)
        }
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // Some fields only accept text once they were clicked into.
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.refresh()
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** The keyboard's action key (搜索 / 发送 / 完成): API 30+ only. */
    fun imeEnter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val id = AccessibilityAction.ACTION_IME_ENTER.id
        node.refresh()
        if (node.actionList.none { it.id == id }) return false
        return node.performAction(id)
    }

    /** Node scroll action for [direction] if the node offers one, else a swipe inside [bounds]. */
    fun scroll(node: AccessibilityNodeInfo?, scrollBits: Int, horizontal: Boolean, bounds: Box, screen: Box, direction: PhoneAction.Direction): Outcome {
        if (node != null) {
            val action = scrollAction(scrollBits, horizontal, direction)
            if (action != null && node.performAction(action)) return Outcome(true, Via.NODE)
        }
        // Keep clear of the status bar and the gesture-navigation strip at the bottom.
        val area = bounds.intersect(Box(screen.left, screen.top + screen.height / 20, screen.right, screen.bottom - screen.height * 6 / 100))
        if (area.isEmpty || area.height < 40 || area.width < 40) return Outcome(false, null)
        val cx = area.centerX
        val cy = area.centerY
        val dy = area.height * 3 / 10
        val dx = area.width * 3 / 10
        val ok = when (direction) {
            // DOWN = show what is below: the finger moves up.
            PhoneAction.Direction.DOWN -> swipe(cx, cy + dy, cx, cy - dy)
            PhoneAction.Direction.UP -> swipe(cx, cy - dy, cx, cy + dy)
            PhoneAction.Direction.RIGHT -> swipe(cx + dx, cy, cx - dx, cy)
            PhoneAction.Direction.LEFT -> swipe(cx - dx, cy, cx + dx, cy)
        }
        return Outcome(ok, if (ok) Via.GESTURE else null)
    }

    private fun scrollAction(bits: Int, horizontal: Boolean, direction: PhoneAction.Direction): Int? = when (direction) {
        PhoneAction.Direction.DOWN -> when {
            bits and Scroll.DOWN != 0 -> AccessibilityAction.ACTION_SCROLL_DOWN.id
            !horizontal && bits and Scroll.FORWARD != 0 -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> null
        }
        PhoneAction.Direction.UP -> when {
            bits and Scroll.UP != 0 -> AccessibilityAction.ACTION_SCROLL_UP.id
            !horizontal && bits and Scroll.BACKWARD != 0 -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> null
        }
        PhoneAction.Direction.RIGHT -> when {
            bits and Scroll.RIGHT != 0 -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
            horizontal && bits and Scroll.FORWARD != 0 -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> null
        }
        PhoneAction.Direction.LEFT -> when {
            bits and Scroll.LEFT != 0 -> AccessibilityAction.ACTION_SCROLL_LEFT.id
            horizontal && bits and Scroll.BACKWARD != 0 -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> null
        }
    }

    fun global(key: PhoneAction.GlobalKey): Boolean = service.performGlobalAction(
        when (key) {
            PhoneAction.GlobalKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            PhoneAction.GlobalKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            PhoneAction.GlobalKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            PhoneAction.GlobalKey.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        },
    )
}
