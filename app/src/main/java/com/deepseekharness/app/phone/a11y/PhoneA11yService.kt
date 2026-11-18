// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's permission. See THIRD_PARTY_NOTICES.md.
// Local changes: registers into PhoneRegistry; stops the task on screen off, interrupt and
// unbind through ControlListener; debug-only probe.

package com.deepseekharness.app.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.deepseekharness.app.phone.PhoneRegistry

/**
 * DSH's accessibility service (same process as the app). While connected it is the MAIN
 * [com.deepseekharness.app.phone.PhoneDriver] and the overlay
 * [com.deepseekharness.app.phone.UserGate]. It reads nothing on its own: the tree is
 * read only when the bridge calls the driver.
 */
open class PhoneA11yService : AccessibilityService() {
    internal val clock = EventClock()
    private var overlay: ControlOverlay? = null
    private var driver: MainScreenDriver? = null
        private var receiverRegistered = false
    private var overlayContext: Context? = null
    @Volatile private var listening = false
    private val uiEvents = UiEventScope(::setListening)

    /** Called only under UiEventScope's short transition lock, never under a UI-thread wait. */
    private fun setListening(active: Boolean) {
        listening = active
        try {
            val info = serviceInfo
            if (active) checkNotNull(info) { "Accessibility connection has closed" }
            if (info != null) {
                info.eventTypes = if (active) UI_EVENT_TYPES else 0
                // AOSP 31/32 sendServiceInfo() clears the client cache, even if the mask is
                // unchanged. There is no public cache-disable API on those versions; their
                // server-side cache-maintenance events can continue after the first query.
                serviceInfo = info
            }
        } finally {
            if (Build.VERSION.SDK_INT >= 33) {
                // Repeat on every final exit: an in-flight node query can re-enable the
                // server-side cache after teardown first disabled it.
                if (!setCacheEnabled(active)) Log.w(TAG, "could not set accessibility cache enabled=$active")
            }
        }
    }

    // Keep package-private Java calls out of inline bytecode: Kotlin cannot generate
    // synthetic accessors into a Java class compiled separately by javac.
    private fun enterUiEvents(): Long = uiEvents.enter()
    private fun exitUiEvents(token: Long) = uiEvents.exit(token)

    internal inline fun <T> withUiEvents(block: () -> T): T {
        val token = enterUiEvents()
        try {
            return block()
        } finally {
            exitUiEvents(token)
        }
    }

    /** A dedicated manager whose default token comes from the live accessibility connection. */
    internal fun overlayWindowContext(): Context = overlayContext ?: run {
        val context = if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            // AccessibilityService's display/window wrappers obtain getOverlayWindowToken(displayId).
            // An application-derived window context cannot supply that accessibility token.
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        } else this
        context.also { overlayContext = it }
    }

    internal fun invalidateOverlayWindowContext() { overlayContext = null }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // l10n:keep agent stop reason
            if (intent.action == Intent.ACTION_SCREEN_OFF) stopAll("屏幕关闭了")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        invalidateOverlayWindowContext()
        uiEvents.connect()
        val o = ControlOverlay(this)
        val d = MainScreenDriver(this, o)
        overlay = o
        driver = d
        instance = this
        PhoneRegistry.mainDriver = d
        PhoneRegistry.overlayGate = o
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenOff, filter)
            receiverRegistered = true
        } catch (e: Exception) { Log.w(TAG, "screen-off receiver", e) }
                Log.i(TAG, "accessibility service connected")
        PhoneRegistry.controlListener?.onAccessibilityChanged(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!listening || event == null || event.eventType and UI_EVENT_TYPES == 0) return
        clock.tick(event.packageName)
    }

    /**
     * The system interrupts feedback (rare for a feedbackGeneric service). The service stays
     * bound, so the registration stays; only the running task stops.
     */
    override fun onInterrupt() {
        // l10n:keep agent stop reason
        stopAll("无障碍服务被系统打断")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // l10n:keep agent stop reason
        teardown("无障碍服务已关闭")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // l10n:keep agent stop reason
        teardown("无障碍服务已停止")
        super.onDestroy()
    }

    /** Screen off, lock, interrupt: abort what runs, answer pending questions with STOP, tell the core. */
    internal fun stopAll(reason: String) {
        driver?.abort()
        overlay?.stopAll()
        PhoneRegistry.controlListener?.onStopRequested(reason)
    }

    private fun teardown(reason: String) {
        if (instance !== this && driver == null) return
        val d = driver
        val o = overlay
        if (PhoneRegistry.mainDriver === d) PhoneRegistry.mainDriver = null
        if (PhoneRegistry.overlayGate === o) PhoneRegistry.overlayGate = null
        if (instance === this) instance = null
        invalidateOverlayWindowContext()
        d?.abort()
        // Do not acquire the driver's operation lock here: the worker may be waiting for
        // the main thread to update the overlay or finish a screenshot.
        runCatching { uiEvents.close() }.onFailure { Log.w(TAG, "disable UI events during teardown", it) }
        o?.destroy()
        d?.close()
                if (receiverRegistered) { runCatching { unregisterReceiver(screenOff) }; receiverRegistered = false }
        driver = null
        overlay = null
        Log.i(TAG, "accessibility service gone: $reason")
        PhoneRegistry.controlListener?.let { it.onStopRequested(reason); it.onAccessibilityChanged(false) }
    }

    companion object {
        private const val TAG = "DshA11y"
        private const val UI_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED

        /** The connected service, if any. */
        @Volatile var instance: PhoneA11yService? = null
            private set

        fun isConnected(): Boolean = instance != null

        fun component(context: Context) = ComponentName(context.packageName, "com.deepseekharness.app.DshaAccessibilityService")

        /** Whether the person switched the service on in system settings (it may not be bound yet). */
        fun isEnabled(context: Context): Boolean {
            val list = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } catch (_: Exception) { null } ?: return false
            val me = component(context)
            return list.split(':').any { entry -> ComponentName.unflattenFromString(entry.trim()) == me }
        }
    }
}
