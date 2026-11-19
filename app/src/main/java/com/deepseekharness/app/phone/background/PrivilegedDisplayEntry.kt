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
 * Legacy Shizuku UserService Binder entry, retained for compatibility; [ShizukuStarter]
 * currently does not connect it. Only this app's uid or the service's own uid may call it;
 * the engine is [PrivilegedDisplayCore], also used by the root and self-ADB stream entry.
 */
@androidx.annotation.Keep
class PrivilegedDisplayService(context: Context) : IPrivilegedDisplay.Stub() {
    private val core = PrivilegedDisplayCore(context)
    private val appUid = context.applicationInfo.uid

    override fun execute(request: Bundle): Bundle {
        if (Binder.getCallingUid() != appUid && Binder.getCallingUid() != Process.myUid()) {
            return Bundle().apply { putBoolean(Proto.OK, false); putString(Proto.ERROR, "identityChanged") }
        }
        if (request.getString(Proto.OP) != Proto.OP_PROBE && request.getString("revision") != Proto.REVISION) {
            return Bundle().apply { putBoolean(Proto.OK, false); putString(Proto.ERROR, "protocolMismatch"); putString("revision", Proto.REVISION) }
        }
        return core.handle(request)
    }
}

/**
 * The root/self-ADB entry, started as `CLASSPATH=<apk> app_process /system/bin <thisClass>`.
 * It speaks the same request/response [Bundle]s as the legacy Binder entry, but framed over
 * stdin/stdout ([Frames] + [Bundles]) because the app has no Binder to look up for this process.
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
