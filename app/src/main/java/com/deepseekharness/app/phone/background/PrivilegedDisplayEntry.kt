// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's permission. See THIRD_PARTY_NOTICES.md.
// Portions derived from DSHA (https://github.com/DSH-APP/DSHA), MIT, Copyright (c) 2026 qiannianhuanxiang. See THIRD_PARTY_NOTICES.md.
package com.deepseekharness.app.phone.background

import android.annotation.SuppressLint
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Process

/**
 * The Shizuku UserService entry: Shizuku instantiates this class (shell or root uid) and
 * hands the app its Binder. Only this app's uid may call it; the real engine is
 * [PrivilegedDisplayCore], shared byte-for-byte with the adb path.
 */
@androidx.annotation.Keep
class PrivilegedDisplayService(context: Context) : IPrivilegedDisplay.Stub() {
    private val core = PrivilegedDisplayCore(context)
    private val appUid = context.applicationInfo.uid

    override fun execute(request: Bundle): Bundle {
        if (Binder.getCallingUid() != appUid && Binder.getCallingUid() != Process.myUid()) {
            return Bundle().apply { putBoolean(Proto.OK, false); putString(Proto.ERROR, "identityChanged") }
        }
        if (request.getString("revision") != null && request.getString("revision") != Proto.REVISION) {
            return Bundle().apply { putBoolean(Proto.OK, false); putString(Proto.ERROR, "identityChanged") }
        }
        return core.handle(request)
    }
}

/**
 * The self-ADB entry, started as `CLASSPATH=<apk> app_process /system/bin <thisClass>` over an
 * adb shell stream. It speaks the same request/response [Bundle]s as the Shizuku binder, but
 * framed over stdin/stdout ([Frames] + [Bundles]) because an adb-launched process has no Binder
 * the app can look up. One privileged engine, two transports.
 */
object PrivilegedDisplayMain {
    @JvmStatic
    @SuppressLint("PrivateApi")
    fun main(args: Array<String>) {
        // ActivityThread.systemMain() constructs Handlers, so prepare before systemContext().
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val context = systemContext()
        val core = PrivilegedDisplayCore(context)
        System.err.println("DSH_VDISPLAY_READY uid=${Process.myUid()} rev=${Proto.REVISION}")
        System.err.flush()
        val worker = Thread({ serve(core) }, "dsh-vdisplay")
        worker.isDaemon = true
        worker.start()
        runCatching { Looper.loop() }
        runCatching { core.handle(Bundle().apply { putString(Proto.OP, Proto.OP_DESTROY) }) }
    }

    private fun serve(core: PrivilegedDisplayCore) {
        val input = System.`in`
        val output = System.out
        try {
            synchronized(output) { output.write(Frames.MAGIC); output.flush() }
            while (true) {
                val frame = Frames.read(input) ?: break
                val request = Bundles.unmarshal(frame)
                val response = core.handle(request)
                synchronized(output) { Frames.write(output, Bundles.marshal(response)) }
                if (request.getString(Proto.OP) == Proto.OP_DESTROY) break
            }
        } catch (_: Throwable) {
            // stream closed or malformed: fall through to exit
        } finally {
            runCatching { core.handle(Bundle().apply { putString(Proto.OP, Proto.OP_DESTROY) }) }
            runCatching { Looper.getMainLooper()?.quitSafely() }
            Process.killProcess(Process.myPid())
        }
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }
}
