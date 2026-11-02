// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's permission. See THIRD_PARTY_NOTICES.md.
// Local changes: blocking with a timeout; API 30–33 full-display capture with the overlay
// hidden; element numbers drawn on the image; near-black frames treated as secure; JPEG, long
// side ≤ 1300 px.

package com.deepseekharness.app.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.Display
import com.deepseekharness.app.phone.SensitiveWords
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** Maps image pixels back to the screen: screen = origin + image / scale. */
internal class ImageGeometry(val originX: Int, val originY: Int, val scaleX: Float, val scaleY: Float, val width: Int, val height: Int) {
    fun toScreen(x: Int, y: Int): Pair<Int, Int>? {
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        return (originX + x / scaleX).roundToInt() to (originY + y / scaleY).roundToInt()
    }
}

internal class Shot(
    val bytes: ByteArray?, val mime: String?, val geometry: ImageGeometry?, val secure: Boolean, val error: String?,
    /** When the system took the frame (device clock, ms), from [AccessibilityService.ScreenshotResult.getTimestamp]. */
    val takenAtMillis: Long? = null,
)

internal class ScreenShooter(private val service: AccessibilityService, private val overlay: ControlOverlay) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-a11y-shot") }

    fun close() { executor.shutdownNow() }

    /**
     * Captures [window] (API 34+, overlay excluded by construction) or the whole display
     * (API 30–33, overlay hidden for the moment of capture), then greys out [secrets] (see
     * [sensitiveBounds]) and draws [marks].
     */
    fun shoot(window: UiWindow?, screen: Box, marks: List<ScreenElement>, secrets: List<Box>, timeoutMs: Long): Shot {
        // l10n:keep agent screenshot result
        if (Build.VERSION.SDK_INT < 30) return Shot(null, null, null, false, "本机系统低于 Android 11，不支持无障碍截图")
        val useWindow = Build.VERSION.SDK_INT >= 34 && window != null
        val raw = if (useWindow) capture(timeoutMs) { cb -> service.takeScreenshotOfWindow(window.id, executor, cb) }
        else {
            overlay.hideForCapture(true)
            try { capture(timeoutMs) { cb -> service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, cb) } }
            finally { overlay.hideForCapture(false) }
        }
        // l10n:keep agent screenshot result
        if (raw.secure) return Shot(null, null, null, true, "此页面禁止截图（安全窗口）")
        // l10n:keep agent screenshot result
        val bitmap = raw.bitmap ?: return Shot(null, null, null, false, raw.error ?: "截图失败")
        try {
            // l10n:keep agent screenshot result
            if (looksBlack(bitmap)) return Shot(null, null, null, true, "截图全黑，可能是禁止截图的页面")
            val takenAt = raw.takenAtMillis
            val origin = if (useWindow) window.bounds else screen
            val factor = minOf(1f, 1300f / max(bitmap.width, bitmap.height))
            val w = (bitmap.width * factor).roundToInt().coerceAtLeast(1)
            val h = (bitmap.height * factor).roundToInt().coerceAtLeast(1)
            var scaled = if (factor < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
            if (scaled === bitmap || !scaled.isMutable) {
                val mutable = scaled.copy(Bitmap.Config.ARGB_8888, true)
                if (scaled !== bitmap) scaled.recycle()
                scaled = mutable
            }
            try {
                val sx = w.toFloat() / origin.width.coerceAtLeast(1)
                val sy = h.toFloat() / origin.height.coerceAtLeast(1)
                draw(scaled, marks, secrets, origin, sx, sy)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                return Shot(out.toByteArray(), "image/jpeg", ImageGeometry(origin.left, origin.top, sx, sy, w, h), false, null, takenAt)
            } finally { scaled.recycle() }
        } finally { bitmap.recycle() }
    }

    private class Raw(val bitmap: Bitmap?, val secure: Boolean, val error: String?, val takenAtMillis: Long? = null)

    private fun capture(timeoutMs: Long, start: (AccessibilityService.TakeScreenshotCallback) -> Unit): Raw {
        if (Build.VERSION.SDK_INT < 30) return Raw(null, false, "unsupported")
        var result: Raw? = null
        repeat(2) { attempt ->
            val latch = CountDownLatch(1)
            var tooSoon = false
            start(object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    result = try {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        hardware?.recycle()
                        // l10n:keep agent screenshot result
                        // ScreenshotResult.getTimestamp(): nanos on the System.nanoTime() clock.
                        val wallClock = System.currentTimeMillis() - (System.nanoTime() - screenshot.timestamp) / 1_000_000L
                        Raw(software, false, if (software == null) "截图失败" else null, wallClock)
                    } catch (_: Throwable) {
                        // l10n:keep agent screenshot result
                        Raw(null, false, "截图失败")
                    } finally { buffer.close() }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    tooSoon = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                    val secure = Build.VERSION.SDK_INT >= 34 && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                    // l10n:keep agent screenshot result
                    result = Raw(null, secure, "截图失败（错误 $errorCode）")
                    latch.countDown()
                }
            })
            // l10n:keep agent screenshot result
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return Raw(null, false, "截图超时")
            // l10n:keep agent screenshot result
            if (!tooSoon || attempt == 1) return result ?: Raw(null, false, "截图失败")
            Thread.sleep(1_050) // the system allows one screenshot per second
        }
        // l10n:keep agent screenshot result
        return result ?: Raw(null, false, "截图失败")
    }

    /** ≥ 97% of a 32×32 sample near-black: a FLAG_SECURE layer blanked out of a display capture. */
    private fun looksBlack(bitmap: Bitmap): Boolean {
        var dark = 0
        val n = 32
        for (i in 0 until n) for (j in 0 until n) {
            val c = bitmap.getPixel((bitmap.width - 1) * i / (n - 1), (bitmap.height - 1) * j / (n - 1))
            if (max(Color.red(c), max(Color.green(c), Color.blue(c))) < 12) dark++
        }
        return dark >= n * n * 97 / 100
    }

    private fun draw(bitmap: Bitmap, marks: List<ScreenElement>, secrets: List<Box>, origin: Box, sx: Float, sy: Float) {
        val canvas = Canvas(bitmap)
        val grey = Paint().apply { color = Color.rgb(128, 128, 128) }
        for (b in secrets) {
            canvas.drawRect((b.left - origin.left) * sx, (b.top - origin.top) * sy, (b.right - origin.left) * sx, (b.bottom - origin.top) * sy, grey)
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(200, 61, 90, 152) }
        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 33, 50, 98) }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.WHITE }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        val metrics = text.fontMetrics
        for (e in marks) {
            if (e.kind == ElementKind.LIST) continue
            val r = RectF((e.bounds.left - origin.left) * sx, (e.bounds.top - origin.top) * sy, (e.bounds.right - origin.left) * sx, (e.bounds.bottom - origin.top) * sy)
            if (r.right <= 0 || r.bottom <= 0 || r.left >= bitmap.width || r.top >= bitmap.height) continue
            canvas.drawRect(r, outline)
            val label = e.id.toString()
            val tw = text.measureText(label) + 8f
            val th = metrics.descent - metrics.ascent + 2f
            val left = r.left.coerceIn(0f, bitmap.width - tw)
            val top = r.top.coerceIn(0f, bitmap.height - th)
            val box = RectF(left, top, left + tw, top + th)
            canvas.drawRoundRect(box, 4f, 4f, tag)
            canvas.drawRoundRect(box, 4f, 4f, halo)
            canvas.drawText(label, left + 4f, top + 1f - metrics.ascent, text)
        }
    }

    companion object {
        /**
         * Password and verification-code fields anywhere in [windows], including ones the text
         * listing left out for length: the screenshot must not show what the text hides.
         */
        fun sensitiveBounds(windows: List<UiWindow>): List<Box> {
            val bounds = ArrayList<Box>()
            fun visit(node: UiNode) {
                val hint = node.hint?.takeIf { it.isNotBlank() } ?: node.text?.takeIf { node.showingHint }
                if (node.password || (node.editable && SensitiveWords.secretHit(hint, node.desc, node.viewId) != null)) {
                    bounds += node.bounds
                }
                node.children.forEach(::visit)
            }
            windows.forEach { it.root?.let(::visit) }
            return bounds
        }
    }
}
