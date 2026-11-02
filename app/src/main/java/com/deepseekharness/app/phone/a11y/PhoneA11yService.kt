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
        clock.tick(event?.packageName)
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
