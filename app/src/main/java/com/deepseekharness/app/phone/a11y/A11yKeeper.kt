package com.deepseekharness.app.phone.a11y

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.deepseekharness.app.phone.PhoneControl

/**
 * 自动恢复无障碍. With phone control switched on in DSHA and WRITE_SECURE_SETTINGS granted
 * (`pm grant` from a computer or a root shell, or the one tap through the privileged channel), it
 * puts [PhoneA11yService] back into ENABLED_ACCESSIBILITY_SERVICES when something took it out (a
 * force stop does, on stock Android too), and takes it out and back in when it stays listed but
 * unbound (the system's 「无法运行」 state) so the system binds it afresh. It writes that list and
 * ACCESSIBILITY_ENABLED, no other setting, and backs off so it can't loop.
 *
 * Checked when [PhoneControl] comes up (the app's start), after an update ([Receiver]), when a
 * task starts or needs the main screen, and on [PhoneControl]'s 2-second tick.
 */
object A11yKeeper {
    private const val TAG = "DshA11yKeeper"

    /** Between taking the service out and putting it back: the system must see both. */
    private const val REBIND_GAP_MS = 1_000L

    private var plan: KeeperPlan? = null

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * One look, and the fix it calls for. True while bringing the service up is the keeper's job
     * (on, granted, not connected), so a caller that needs it may wait a moment. Any thread; a
     * rebind holds it about a second.
     */
    @Synchronized
    fun check(context: Context): Boolean {
        val app = context.applicationContext
        val plan = plan ?: KeeperPlan(app.packageName, "com.deepseekharness.app.DshaAccessibilityService").also { plan = it }
        val on = PhoneControl.switchedOn(app)
        val connected = PhoneA11yService.isConnected()
        if (on && !connected && !granted(app)) {
            runCatching { com.deepseekharness.app.DshaAccessibilityService.ensureConnected(app) }
        }
        val granted = on && !connected && granted(app)
        val list = if (granted) read(app) else null
        val step = plan.next(on, granted, list, connected, SystemClock.elapsedRealtime())
        try {
            when (step) {
                KeeperPlan.Step.ADD -> write(app, plan.withMe(list), enable = true)
                KeeperPlan.Step.REBIND -> {
                    write(app, plan.withoutMe(list), enable = false)
                    SystemClock.sleep(REBIND_GAP_MS)
                    // The person can turn phone control off or revoke the grant while we wait.
                    if (!PhoneControl.switchedOn(app) || !granted(app)) return false
                    write(app, plan.withMe(read(app)), enable = true)
                }
                KeeperPlan.Step.NONE -> return granted
            }
            Log.i(TAG, "accessibility service ${if (step == KeeperPlan.Step.ADD) "re-added" else "rebound"}")
        } catch (e: Exception) {
            Log.w(TAG, "restore failed", e)
        }
        return granted
    }

    private fun read(app: Context): String? =
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

    private fun write(app: Context, list: String, enable: Boolean) {
        check(Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list))
        if (enable) check(Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1))
    }

    /** MY_PACKAGE_REPLACED: the updated app starts here, and may start without its service. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val pending = goAsync()
            Thread({
                try {
                    check(context)
                } finally {
                    pending.finish()
                }
            }, "dsh-a11y-keeper").start()
        }
    }
}

/**
 * The keeper's decisions, apart from Android so they are unit-tested. ENABLED_ACCESSIBILITY_SERVICES
 * is `pkg/cls` or `pkg/.Short` entries joined by ':'; edits touch only ours and keep every other
 * entry as it was.
 */
class KeeperPlan(private val pkg: String, private val cls: String) {
    enum class Step { NONE, ADD, REBIND }

    private val me = "$pkg/$cls"
    private var unboundSince = NEVER
    private var heldSince = NEVER
    private var writes = 0
    private var nextAt = 0L

    /**
     * What to do at [now]: [on] — phone control is switched on in DSHA; [granted] —
     * WRITE_SECURE_SETTINGS is held; [list] — the setting as read; [connected] — our service is bound.
     */
    fun next(on: Boolean, granted: Boolean, list: String?, connected: Boolean, now: Long): Step {
        if (!on) {
            unboundSince = NEVER
            heldSince = NEVER
            writes = 0
            nextAt = 0L
            return Step.NONE
        }
        if (connected) {
            unboundSince = NEVER
            if (heldSince == NEVER) heldSince = now
            // Held a while: whatever took it away before was not a fight, start the backoff over.
            if (now - heldSince >= STABLE_MS) {
                writes = 0
                nextAt = 0L
            }
            return Step.NONE
        }
        heldSince = NEVER
        if (!granted) {
            unboundSince = NEVER
            return Step.NONE
        }
        val step = if (!listed(list)) {
            Step.ADD
        } else {
            if (unboundSince == NEVER) unboundSince = now
            // Listed and not bound yet: the system is still binding, or gave up on it.
            if (now - unboundSince < UNBOUND_MS) return Step.NONE
            Step.REBIND
        }
        if (now < nextAt) return Step.NONE
        writes = (writes + 1).coerceAtMost(8)
        nextAt = now + backoff(writes)
        unboundSince = NEVER
        return step
    }

    fun listed(list: String?): Boolean = list.orEmpty().split(':').any(::ours)

    /** [list] with ours at the end (unchanged when it is there already). */
    fun withMe(list: String?): String = when {
        listed(list) -> list.orEmpty()
        list.isNullOrEmpty() -> me
        else -> "$list:$me"
    }

    /** [list] without ours. */
    fun withoutMe(list: String?): String = list.orEmpty().split(':').filterNot(::ours).joinToString(":")

    private fun ours(entry: String): Boolean {
        val e = entry.trim()
        val slash = e.indexOf('/')
        if (slash <= 0 || e.substring(0, slash) != pkg) return false
        val c = e.substring(slash + 1)
        return c == cls || (c.startsWith('.') && pkg + c == cls)
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /** Listed but unbound this long: the system won't bind it by itself. */
        const val UNBOUND_MS = 10_000L

        /** Bound this long: the next loss starts the backoff over. */
        const val STABLE_MS = 60_000L

        private const val FIRST_WAIT_MS = 5_000L
        private const val MAX_WAIT_MS = 10 * 60_000L

        /** After the [n]th write in a row: 5 s, 10 s, 20 s … at most 10 minutes. */
        fun backoff(n: Int): Long = minOf(FIRST_WAIT_MS shl (n - 1).coerceIn(0, 7), MAX_WAIT_MS)
    }
}
