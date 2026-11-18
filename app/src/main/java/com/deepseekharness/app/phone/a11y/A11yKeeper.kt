package com.deepseekharness.app.phone.a11y

import android.Manifest
import android.content.Context
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
 * Checked only when an MCP call needs the main-screen driver. Enabling the feature, app startup,
 * updates and task timers never restore the service or acquire Root permission.
 */
object A11yKeeper {
    private const val TAG = "DshA11yKeeper"

    /** Between taking the service out and putting it back: the system must see both. */
    private const val REBIND_GAP_MS = 1_000L

    private const val PREFS = "dsh_phone"
    private const val K_ENABLED = "a11yAutoRestore"
    private const val K_ERROR = "a11yRestoreError"

    fun error(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_ERROR, null)

    private fun recordError(context: Context, message: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_ERROR, message).apply()
    }

    private fun suspendLocked(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ENABLED, false).putString(K_ERROR, message).apply()
        resetLocked()
    }

    // Keep the switch independent of check's serialization: its rebind gap must not hold up UI.
    private val stateLock = Any()
    private var generation = 0L
    private var plan: KeeperPlan? = null

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        synchronized(stateLock) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(K_ENABLED, enabled).remove(K_ERROR).apply()
            resetLocked()
        }
    }

    /** Call after changing the master switch, including off/on during a pending rebind. */
    fun reset(context: Context) {
        synchronized(stateLock) {
            // A master-switch shutdown makes any prior failure stale; the next explicit
            // enable starts with a clean status while the auto-restore preference stays independent.
            if (!PhoneControl.switchedOn(context)) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(K_ERROR).apply()
            }
            resetLocked()
        }
    }

    private fun resetLocked() {
        generation++
        plan?.reset()
    }

    private fun active(app: Context): Boolean = PhoneControl.switchedOn(app) && enabled(app)

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
        val attempt = synchronized(stateLock) {
            val plan = plan ?: KeeperPlan(app.packageName, "com.deepseekharness.app.DshaAccessibilityService").also { plan = it }
            val on = active(app)
            val connected = PhoneA11yService.isConnected()
            if (!on) return false
            // Missing or revoked permission is never a reason to launch su in the background.
            if (!granted(app)) {
                suspendLocked(app, "自动恢复已停止：缺少修改系统设置权限，请重新打开「自动恢复无障碍」以申请授权。")
                return false
            }
            val now = SystemClock.elapsedRealtime()
            if (!connected && plan.exhausted(now)) {
                val cause = error(app) ?: "系统在多次恢复后仍未连接无障碍服务"
                suspendLocked(app, "已停止自动恢复（达到 ${KeeperPlan.MAX_ATTEMPTS} 次上限）：$cause")
                return false
            }
            val list = try { if (connected) null else read(app) } catch (e: Exception) {
                suspendLocked(app, "无法读取无障碍设置：${e.message ?: e.javaClass.simpleName}")
                return false
            }
            // This check lives inside a bounded MCP call, so allow a short binding grace
            // before rebind instead of requiring a persistent background polling loop.
            val step = plan.next(on, true, list, connected, now, bindingGraceMs = 1_000L)
            if (connected) recordError(app, null)
            Attempt(plan, step, generation)
        }
        try {
            when (attempt.step) {
                KeeperPlan.Step.ADD -> synchronized(stateLock) {
                    if (!current(app, attempt) || !granted(app)) return false
                    if (!PhoneA11yService.isConnected()) {
                        write(app, attempt.plan.withMe(read(app)), enable = true)
                        recordError(app, null)
                    }
                }
                KeeperPlan.Step.REBIND -> {
                    synchronized(stateLock) {
                        if (!current(app, attempt) || !granted(app)) return false
                        if (PhoneA11yService.isConnected()) return false
                        write(app, attempt.plan.withoutMe(read(app)), enable = false)
                    }
                    SystemClock.sleep(REBIND_GAP_MS)
                    // The person can turn phone control off or revoke the grant while we wait.
                    synchronized(stateLock) {
                        if (!current(app, attempt) || !granted(app)) return false
                        write(app, attempt.plan.withMe(read(app)), enable = true)
                        recordError(app, null)
                    }
                }
                KeeperPlan.Step.NONE -> Unit
            }
            if (attempt.step == KeeperPlan.Step.ADD || attempt.step == KeeperPlan.Step.REBIND) {
                Log.i(TAG, "accessibility service ${if (attempt.step == KeeperPlan.Step.ADD) "re-added" else "rebound"}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restore failed", e)
            synchronized(stateLock) {
                if (current(app, attempt)) {
                    val reason = "恢复失败：${e.message ?: e.javaClass.simpleName}"
                    if (e is SecurityException) suspendLocked(app, reason) else recordError(app, reason)
                }
            }
        }
        return synchronized(stateLock) {
            current(app, attempt) && !PhoneA11yService.isConnected() && !attempt.plan.exhausted(SystemClock.elapsedRealtime())
        }
    }

    private data class Attempt(val plan: KeeperPlan, val step: KeeperPlan.Step, val generation: Long)

    // stateLock is held by callers, including each settings write, so disabling cannot race it.
    private fun current(app: Context, attempt: Attempt): Boolean {
        if (!active(app)) {
            resetLocked()
            return false
        }
        return generation == attempt.generation
    }

    private fun read(app: Context): String? =
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

    private fun write(app: Context, list: String, enable: Boolean) {
        check(Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)) {
            "系统拒绝更新无障碍服务列表"
        }
        if (enable) check(Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)) {
            "系统拒绝开启无障碍总开关"
        }
    }

}
