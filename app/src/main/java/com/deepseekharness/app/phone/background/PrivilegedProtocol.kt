// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's permission. See THIRD_PARTY_NOTICES.md.
package com.deepseekharness.app.phone.background

import android.os.Bundle
import android.os.Parcel
import java.io.InputStream
import java.io.OutputStream

// The one request/response contract the privileged engine speaks, independent of how
// it is reached: a Shizuku Binder (IPrivilegedDisplay) or an adb `app_process` stdin/
// stdout stream (PrivilegedDisplayMain). Both carry the same [Bundle]; the engine
// ([PrivilegedDisplayCore]) only ever sees a Bundle in and a Bundle out. The engine
// accepts only this fixed verb set — no shell commands, paths or display ids from callers.

object Proto {
    const val REVISION = "dsh-vdisplay-1"

    // request keys
    const val OP = "op"
    const val OWNER = "owner"
    const val CALL = "call"
    const val PKG = "pkg"
    const val SHORT_SIDE = "shortSide"
    const val ASPECT_W = "aspectW"
    const val ASPECT_H = "aspectH"
    const val ELEMENT = "element"
    const val EXPECT_ROLE = "expectRole"
    const val EXPECT_LABEL = "expectLabel"
    const val EXPECT_VIEW_ID = "expectViewId"
    const val TEXT = "text"
    const val SUBMIT = "submit"

    /** `text` only: the person allowed password / code inputs (absent, as from an older app: no). */
    const val ALLOW_SECRET = "allowSecret"
    const val LONG = "long"
    const val DIR = "dir"
    const val WANT_IMAGE = "image"
    const val DETAIL = "detail"

    // ops
    const val OP_PROBE = "probe"
    const val OP_CREATE = "create"
    const val OP_LAUNCH = "launch"
    const val OP_OBSERVE = "observe"
    const val OP_TAP = "tap"
    const val OP_SCROLL = "scroll"
    const val OP_KEY = "key"
    const val OP_TEXT = "text"
    const val OP_RELEASE = "release"
    const val OP_DESTROY = "destroy"
    const val OP_GATE = "gate"

    /** 自动恢复无障碍: grants [APP_PACKAGE] WRITE_SECURE_SETTINGS in [USER]; nothing else is grantable. */
    const val OP_GRANT_SECURE_SETTINGS = "grantSecureSettings"
    const val USER = "user"
    const val APP_PACKAGE = "com.dsha.fr"

    const val INJECT_OK = "injectOk"

    // response keys
    const val OK = "ok"
    const val ERROR = "error"
    const val DISPLAY_ID = "displayId"
    const val UID = "uid"
    const val SECURE = "secure"
    const val POOR = "poorTree"
    const val PORTRAIT = "portrait"
    const val KEYBOARD_UP = "keyboardUp"
    const val IMAGE_OMITTED = "imageOmitted"
    const val APP_LABEL = "appLabel"
    const val FG_PKG = "foregroundPkg"
    const val ACCEPTED = "accepted"
    const val READBACK = "readback"
    const val COUNT = "n"
    // parallel element arrays (index i ↔ element id i+1)
    const val A_ROLE = "role"
    const val A_LABEL = "label"
    const val A_VIEW_ID = "viewId"
    const val A_STATES = "states"       // states joined by \u0001
    const val A_FLAGS = "flags"         // bit0 password, 1 secret, 2 editable, 3 clickable,
    //                                     4 longClickable, 5 scrollable, 6 sensitiveWord, 7 focused
    const val A_REGION = "region"       // 0 top, 1 middle, 2 bottom
    const val A_INDENT = "indent"

    const val FLAG_PASSWORD = 1
    const val FLAG_SECRET = 1 shl 1
    const val FLAG_EDITABLE = 1 shl 2
    const val FLAG_CLICKABLE = 1 shl 3
    const val FLAG_LONGCLICK = 1 shl 4
    const val FLAG_SCROLLABLE = 1 shl 5
    const val FLAG_SENSITIVE = 1 shl 6
    const val FLAG_FOCUSED = 1 shl 7
}

/** Stable reason codes → one short Chinese sentence for the model (after Phase's set). */
object Reasons {
    fun of(code: String?): String = when (code) {
        null -> ""
        // l10n:keep privileged protocol
        "identityChanged" -> "特权通道身份已改变，请重新开始任务"
        // l10n:keep privileged protocol
        "permissionRequired", "channelDisabled" -> "不打扰模式未启用或授权已撤销"
        // l10n:keep privileged protocol
        "systemOperationDenied" -> "系统拒绝了后台屏操作"
        // l10n:keep privileged protocol
        "launchDenied" -> "系统拒绝在后台屏启动此应用"
        // l10n:keep privileged protocol
        "launchCancelled" -> "后台屏应用启动请求已取消，未自动重试"
        // l10n:keep privileged protocol
        "notLaunchable" -> "此应用没有可启动的界面"
        // l10n:keep privileged protocol
        "displayUnsupported" -> "此系统不支持所需的独立焦点虚拟屏，未改用主屏"
        // l10n:keep privileged protocol
        "displayReleaseFailed" -> "未收到后台屏资源释放的完整回执"
        // l10n:keep privileged protocol
        "displayMissing" -> "本次任务还没有后台屏，请先启动目标应用"
        // l10n:keep privileged protocol
        "displayTargetChanged" -> "后台屏前台任务不属于目标应用，未继续操作"
        // l10n:keep privileged protocol
        "secureWindow" -> "此页面受保护（支付或密码），请交还用户在主屏完成"
        // l10n:keep privileged protocol
        "staleSnapshot" -> "后台屏快照已过期，请重新观察后再操作"
        // l10n:keep privileged protocol
        "elementMissing" -> "该元素已不在后台屏当前界面上"
        // l10n:keep privileged protocol
        "textUnsupported" -> "此输入框不支持直接写入文字"
        // l10n:keep privileged protocol
        "textConnectionUnavailable" -> "后台屏文字输入服务不可用"
        // l10n:keep privileged protocol
        "textFocusMissing" -> "后台屏上没有已聚焦的输入框"
        // l10n:keep privileged protocol
        "textTargetChanged" -> "后台屏输入框或焦点已改变，未继续输入"
        // l10n:keep privileged protocol
        "textRequiresManual" -> "此输入框需要用户手动输入"
        // l10n:keep privileged protocol
        "textReadbackMismatch" -> "文字已提交，但输入框内容与预期不一致"
        // l10n:keep privileged protocol
        "inputRejected" -> "系统拒绝了后台屏输入，未重试动作"
        // l10n:keep privileged protocol
        "screenshotFailed" -> "未取得后台屏画面，可重新观察；不要重放已派发的动作"
        // l10n:keep privileged protocol
        "treeUnavailable" -> "未能读取后台屏控件树，请重新观察"
        // l10n:keep privileged protocol
        "locked" -> "设备已锁定，后台屏操作已停止"
        // l10n:keep privileged protocol
        "invalidArguments" -> "后台屏动作参数无效"
        // l10n:keep privileged protocol
        "cancelled" -> "后台屏操作已停止，已派发动作不代表已撤销"
        // l10n:keep privileged protocol
        "timeout" -> "后台屏操作超时，已派发动作不代表已撤销"
        // l10n:keep privileged protocol
        else -> "不打扰模式暂不可用，请检查授权、服务和设备支持情况"
    }
}

/** Length-prefixed frames for the adb stream transport. Pure java.io, JVM-testable. */
object Frames {
    const val MAX = 8 * 1024 * 1024

    /** Written once before the first frame so the reader can skip leading shell/stderr noise. */
    val MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), 'V'.code.toByte())

    /** Reads and discards input until [MAGIC] is matched; false at end of stream. */
    fun syncToMagic(input: InputStream): Boolean {
        var matched = 0
        while (matched < MAGIC.size) {
            val b = input.read()
            if (b < 0) return false
            matched = if (b.toByte() == MAGIC[matched]) matched + 1
            else if (b.toByte() == MAGIC[0]) 1 else 0
        }
        return true
    }

    fun write(out: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX) { "frame too large" }
        val n = payload.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(payload)
        out.flush()
    }

    /** Blocks for a full frame; null at clean end of stream. */
    fun read(input: InputStream): ByteArray? {
        val header = readFully(input, 4) ?: return null
        val n = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (n < 0 || n > MAX) throw java.io.IOException("bad frame length $n")
        return readFully(input, n) ?: throw java.io.IOException("truncated frame")
    }

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return if (off == 0) null else throw java.io.IOException("truncated")
            off += r
        }
        return buf
    }
}

/** Bundle ↔ bytes via Parcel (Android only). Requests/responses hold primitives, String[] and byte[]. */
object Bundles {
    fun marshal(bundle: Bundle): ByteArray {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    fun unmarshal(bytes: ByteArray): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return parcel.readBundle(Bundles::class.java.classLoader) ?: Bundle()
        } finally {
            parcel.recycle()
        }
    }
}
