// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's permission. See THIRD_PARTY_NOTICES.md.
// Local changes: blocking, one owner per task (the bridge serialises calls on one worker
// thread); a UiAutomation control-tree read rendered to the screen text format, so text-only
// models can use 不打扰模式 too; IME policy HIDE; JPEG capture with black-frame → secure; display
// sized to the phone's aspect (short side 720). No commands, paths or display ids are accepted
// from callers.
package com.deepseekharness.app.phone.background

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.text.InputType
import android.util.SparseArray
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.deepseekharness.app.phone.SensitiveWords
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs as root or shell uid in the app_process started by RootStarter or AdbStarter.
 * Owns at most one trusted virtual display at a time (one per task) and all the
 * privileged primitives against it. Thread-safe via a single lock; every op has its own
 * deadline. Never throws across the call boundary: faults become a stable [Proto.ERROR] code.
 */
class PrivilegedDisplayCore(private val context: Context) {

    private val privileged: Context by lazy {
        if (Process.myUid() == Process.SHELL_UID)
            context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        else context
    }
    private val displays by lazy { privileged.getSystemService(DisplayManager::class.java) }
    private val activities by lazy { privileged.getSystemService(ActivityManager::class.java) }
    private val keyguard by lazy { privileged.getSystemService(KeyguardManager::class.java) }
    private val taskDisplayIdField by lazy {
        runCatching { ActivityManager.RunningTaskInfo::class.java.getField("displayId") }.getOrNull()
    }

    private val snapshots = BackgroundRepairSnapshots()
    private val lock = Any()
    @Volatile private var owner: String? = null
    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private var width = 0
    private var height = 0
    private val released = AtomicBoolean(false)

    private class Fault(val code: String) : Exception()

    /** The single entry point for both transports. Always returns a response Bundle. */
    fun handle(request: Bundle): Bundle {
        val response = Bundle().apply { putString("revision", Proto.REVISION) }
        val op = request.getString(Proto.OP)
        try {
            if (op == Proto.OP_PROBE) {
                response.putBoolean(Proto.OK, true)
                response.putInt(Proto.UID, Process.myUid())
                response.putString("revision", Proto.REVISION)
                return response
            }
            if (op != Proto.OP_PROBE && op != Proto.OP_DESTROY && request.getString("revision") != Proto.REVISION) throw Fault("protocolMismatch")
            if (op == Proto.OP_DESTROY) {
                releaseAll()
                response.putBoolean(Proto.OK, true)
                return response
            }
            if (op == Proto.OP_GATE) {
                synchronized(lock) { gateProbe(response) }
                if (!response.containsKey(Proto.ERROR)) response.putBoolean(Proto.OK, true)
                return response
            }
            if (op == Proto.OP_GRANT_SECURE_SETTINGS) {
                grantSecureSettings(request.getInt(Proto.USER))
                response.putBoolean(Proto.OK, true)
                return response
            }
            synchronized(lock) { dispatch(op, request, response) }
            if (!response.containsKey(Proto.ERROR)) response.putBoolean(Proto.OK, true)
        } catch (f: Fault) {
            response.putBoolean(Proto.OK, false)
            response.putString(Proto.ERROR, f.code)
        } catch (_: SecurityException) {
            response.putBoolean(Proto.OK, false)
            response.putString(Proto.ERROR, "systemOperationDenied")
        } catch (_: Throwable) {
            response.putBoolean(Proto.OK, false)
            response.putString(Proto.ERROR, "deviceUnavailable")
        }
        return response
    }

    private fun dispatch(op: String?, request: Bundle, response: Bundle) {
        val ownerId = request.getString(Proto.OWNER).orEmpty()
        if (op == null || ownerId.isEmpty()) throw Fault("invalidArguments")
        if (op == Proto.OP_RELEASE) {
            if (owner != null && owner != ownerId) throw Fault("identityChanged")
            if (owner == ownerId) releaseAll()
            response.putBoolean("released", true)
            return
        }
        if (owner != null && owner != ownerId) throw Fault("identityChanged")
        if (keyguard?.isKeyguardLocked == true) throw Fault("locked")
        val pkg = request.getString(Proto.PKG).orEmpty()
        if (!PACKAGE.matches(pkg)) throw Fault("invalidArguments")

        when (op) {
            Proto.OP_CREATE, Proto.OP_LAUNCH -> {
                owner = ownerId
                if (display == null) createDisplay(request)
                response.putInt(Proto.DISPLAY_ID, displayId())
                launchPackage(pkg)
                sleepChecked(300)
                observe(pkg, request.getBoolean(Proto.WANT_IMAGE), response)
            }
            Proto.OP_PRESENT -> {
                requireDisplay()
                present(pkg, response)
            }
            Proto.OP_OBSERVE -> {
                requireDisplay(); requireForeground(pkg)
                observe(pkg, request.getBoolean(Proto.WANT_IMAGE), response)
            }
            Proto.OP_VALIDATE -> {
                requireDisplay(); requireForeground(pkg)
                withTarget(pkg, request) { _, _, _ -> response.putBoolean("validated", true) }
            }
            Proto.OP_TAP -> {
                requireDisplay(); requireForeground(pkg)
                tap(pkg, request)
                sleepChecked(250)
                observe(pkg, false, response)
            }
            Proto.OP_SCROLL -> {
                requireDisplay(); requireForeground(pkg)
                scroll(pkg, request, response)
                sleepChecked(250)
                observe(pkg, false, response)
            }
            Proto.OP_KEY -> {
                requireDisplay(); requireForeground(pkg)
                validateSnapshot(request)
                snapshots.clear()
                globalKey(request.getString(Proto.DIR))
                sleepChecked(250)
                observe(pkg, false, response)
            }
            Proto.OP_TEXT -> {
                requireDisplay(); requireForeground(pkg)
                typeText(pkg, request, response)
                sleepChecked(200)
                observe(pkg, false, response)
            }
            else -> throw Fault("invalidArguments")
        }
    }

    /**
     * Capability probe: create a tiny trusted display (fails with displayUnsupported on 15-QPR2-type
     * ROMs) and inject one harmless event (fails on Xiaomi without 「USB 调试(安全设置)」). Then
     * release. Reports [Proto.OK] + [Proto.INJECT_OK]; never launches an app.
     */
    private fun gateProbe(response: Bundle) {
        try {
            owner = "__gate__"
            createDisplay(Bundle().apply { putInt(Proto.SHORT_SIDE, 360); putInt(Proto.ASPECT_W, 9); putInt(Proto.ASPECT_H, 20) })
            val injectOk = runCatching { injectTap(1, 1, false); true }.getOrDefault(false)
            response.putBoolean(Proto.INJECT_OK, injectOk)
            response.putInt(Proto.DISPLAY_ID, displayId())
        } finally {
            releaseAll()
        }
    }

    // ---- display lifecycle -------------------------------------------------

    private fun displayId(): Int = display?.display?.displayId ?: throw Fault("displayMissing")
    private fun requireDisplay() { if (display == null) throw Fault("displayMissing") }

    private fun createDisplay(request: Bundle) {
        val shortSide = request.getInt(Proto.SHORT_SIDE, 720).coerceIn(360, 1080)
        val aspectW = request.getInt(Proto.ASPECT_W, 9).coerceAtLeast(1)
        val aspectH = request.getInt(Proto.ASPECT_H, 20).coerceAtLeast(1)
        val size = BackgroundPolicy.displaySize(shortSide, aspectW, aspectH)
        val w = size[0]; val h = size[1]
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        try {
            // Every flag must resolve; never silently make a focus-stealing display on an old ROM.
            // SECURE is deliberately not requested, so protected content stays protected.
            val flags = try {
                FLAG_NAMES.fold(0) { bits, name ->
                    bits or DisplayManager::class.java.getDeclaredField("VIRTUAL_DISPLAY_FLAG_$name").getInt(null)
                }
            } catch (_: ReflectiveOperationException) { throw Fault("displayUnsupported") }
            val vd = displays.createVirtualDisplay("dsh-background", w, h, 320, ir.surface, flags)
            if (vd.display.displayId <= Display.DEFAULT_DISPLAY) { vd.release(); throw Fault("displayUnsupported") }
            width = w; height = h; reader = ir; display = vd
            // Keep the virtual display's keyboard off the main screen: IME policy HIDE.
            runCatching { setImePolicyHide(vd.display.displayId) }
        } catch (e: Throwable) { ir.close(); if (e is Fault) throw e else throw Fault("displayUnsupported") }
    }

    private fun launchPackage(pkg: String) {
        val display = this.display ?: throw Fault("displayMissing")
        val intent = privileged.packageManager.getLaunchIntentForPackage(pkg) ?: throw Fault("notLaunchable")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = display.display.displayId }
        // A shell/UserService is not an ActivityManager-attached app process: a PendingIntent
        // avoids Context.startActivity's caller check (Phase, after Aether).
        var pending: PendingIntent? = null
        try {
            pending = PendingIntent.getActivity(
                privileged.createDisplayContext(display.display), display.display.displayId, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            pending.send(privileged, 0, null, null, null, null, options.toBundle())
        } catch (_: SecurityException) { throw Fault("launchDenied") }
        catch (_: PendingIntent.CanceledException) { throw Fault("launchCancelled") }
        finally { pending?.let { runCatching { it.cancel() } } }
        // Poll for the target becoming foreground on the owned display.
        val deadline = SystemClock.uptimeMillis() + 4000
        while (true) {
            if (foregroundPackage() == pkg) break
            if (SystemClock.uptimeMillis() >= deadline) throw Fault("displayTargetChanged")
            sleepChecked(120)
        }
    }

    private fun requireForeground(pkg: String) {
        if (foregroundPackage() != pkg) throw Fault("displayTargetChanged")
    }

    private fun foregroundPackage(): String? {
        val id = display?.display?.displayId ?: return null
        val field = taskDisplayIdField ?: return null
        @Suppress("DEPRECATION")
        val task = activities.getRunningTasks(50).firstOrNull { runCatching { field.getInt(it) }.getOrDefault(-1) == id }
        return task?.topActivity?.packageName
    }

    private fun releaseAll() = synchronized(lock) {
        owner = null
        snapshots.clear()
        var failed = false
        try { display?.release() } catch (_: Throwable) { failed = true } finally { display = null }
        try { reader?.close() } catch (_: Throwable) { failed = true } finally { reader = null }
        if (failed) throw Fault("displayReleaseFailed")
    }

    // ---- observation (tree + optional screenshot) --------------------------

    private fun observe(pkg: String, wantImage: Boolean, response: Bundle) {
        val id = displayId()
        response.putInt(Proto.DISPLAY_ID, id)
        response.putString(Proto.FG_PKG, pkg)
        response.putBoolean(Proto.PORTRAIT, height >= width)
        val shot = runCatching { capture() }.getOrNull()
        var secure = shot?.black == true
        val label = runCatching { appLabel(pkg) }.getOrNull()
        label?.let { response.putString(Proto.APP_LABEL, it) }
        val tree = runCatching { readTree(id, pkg) }.getOrElse {
            if (it is Fault && it.code == "secureWindow") secure = true
            null
        }
        response.putBoolean(Proto.SECURE, secure)
        if (secure) { snapshots.clear(); response.putBoolean(Proto.POOR, false); response.putInt(Proto.COUNT, 0); response.putString("status", "needs_user"); return }
        // The screenshot contains the whole viewport, including nodes beyond the 120-line
        // text limit. Never attach it when secrets were found or the scan was incomplete.
        // The typing unlock changes permission to write, never permission to reveal a secret.
        if (wantImage && shot != null) {
            if (tree != null && tree.complete && !tree.hasSecrets) {
                response.putByteArray("imageBytes", shot.jpeg)
                response.putString("imageMime", "image/jpeg")
            } else {
                response.putBoolean(Proto.IMAGE_OMITTED, true)
            }
        }
        emitElements(tree?.elements.orEmpty(), response)
        response.putBoolean(Proto.COMPLETE, tree?.complete == true && !tree.labelsTruncated && tree.elements.size <= 120)
    }

    private fun emitElements(elements: List<BgNode>, response: Bundle) {
        val capped = elements.take(120)
        val n = capped.size
        response.putInt(Proto.COUNT, n)
        response.putBoolean(Proto.POOR, elements.count { it.label.isNotEmpty() } < 5)
        val role = arrayOfNulls<String>(n); val label = arrayOfNulls<String>(n)
        val rowIndices = IntArray(n); val rowCounts = IntArray(n)
        val starts = BooleanArray(n); val ends = BooleanArray(n)
        val parents = IntArray(n); val paths = arrayOfNulls<String>(n)
        val fingerprints = arrayOfNulls<String>(n); val containers = arrayOfNulls<String>(n)
        val windows = IntArray(n); val classes = arrayOfNulls<String>(n); val bounds = IntArray(n * 4)
        val viewIds = arrayOfNulls<String>(n)
        val states = arrayOfNulls<String>(n); val flags = IntArray(n)
        val region = IntArray(n); val indent = IntArray(n)
        for (i in 0 until n) {
            val e = capped[i]
            rowIndices[i] = e.rowIndex; rowCounts[i] = e.rowCount
            starts[i] = e.atListStart; ends[i] = e.atListEnd
            parents[i] = e.parentId; paths[i] = e.path; fingerprints[i] = e.fingerprint
            containers[i] = e.containerIdentity; windows[i] = e.windowId; classes[i] = e.className
            bounds[i * 4] = e.bounds.left; bounds[i * 4 + 1] = e.bounds.top
            bounds[i * 4 + 2] = e.bounds.right; bounds[i * 4 + 3] = e.bounds.bottom
            role[i] = e.role; label[i] = e.label; states[i] = e.states.joinToString("\u0001")
            viewIds[i] = e.viewId
            flags[i] = e.flags; region[i] = e.region; indent[i] = e.indent
        }
        response.putString(Proto.SNAPSHOT, snapshots.publish(owner ?: throw Fault("identityChanged"), capped.map { it.fingerprint }))
        response.putIntArray(Proto.A_ROW, rowIndices); response.putIntArray(Proto.A_ROWS, rowCounts)
        response.putBooleanArray(Proto.A_START, starts); response.putBooleanArray(Proto.A_END, ends)
        response.putIntArray(Proto.A_PARENT, parents); response.putStringArray(Proto.A_PATH, paths)
        response.putStringArray(Proto.A_FINGERPRINT, fingerprints); response.putStringArray(Proto.A_CONTAINER, containers)
        response.putIntArray(Proto.A_WINDOW, windows); response.putStringArray(Proto.A_CLASS, classes)
        response.putIntArray(Proto.A_BOUNDS, bounds)
        response.putStringArray(Proto.A_ROLE, role)
        response.putStringArray(Proto.A_LABEL, label)
        response.putStringArray(Proto.A_VIEW_ID, viewIds)
        response.putStringArray(Proto.A_STATES, states)
        response.putIntArray(Proto.A_FLAGS, flags)
        response.putIntArray(Proto.A_REGION, region)
        response.putIntArray(Proto.A_INDENT, indent)
    }

    private fun appLabel(pkg: String): String? {
        val pm = privileged.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
    }

    private class CaptureResult(val jpeg: ByteArray, val black: Boolean)

    private fun capture(): CaptureResult {
        val ir = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        try {
            synchronized(lock) {
                val vd = display ?: throw Fault("displayMissing")
                val previous = reader
                vd.surface = ir.surface
                reader = ir
                previous?.close()
            }
            val deadline = SystemClock.uptimeMillis() + 2500
            var image = ir.acquireLatestImage()
            while (image == null) {
                if (SystemClock.uptimeMillis() >= deadline) throw Fault("screenshotFailed")
                sleepChecked(40)
                image = ir.acquireLatestImage()
            }
            try {
                val plane = image.planes.singleOrNull() ?: throw Fault("screenshotFailed")
                if (plane.pixelStride != 4 || plane.rowStride < width * 4) throw Fault("screenshotFailed")
                val pixels = ByteBuffer.allocate(width * height * 4)
                val src = plane.buffer.duplicate()
                for (row in 0 until height) {
                    val start = row * plane.rowStride
                    src.position(start); src.limit(start + width * 4)
                    pixels.put(src)
                }
                pixels.flip()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.copyPixelsFromBuffer(pixels)
                    val black = isMostlyBlack(bitmap)
                    val buffer = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, buffer)
                    return CaptureResult(buffer.toByteArray(), black)
                } finally { bitmap.recycle() }
            } finally { image.close() }
        } finally { /* reader kept for next frame; closed on release */ }
    }

    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 48).coerceAtLeast(1)
        var dark = 0; var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val luma = ((c shr 16 and 0xff) * 30 + (c shr 8 and 0xff) * 59 + (c and 0xff) * 11) / 100
                if (luma < 12) dark++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && dark * 100 / total >= 98
    }

    // ---- UiAutomation control-tree read ------------------------------------

    private class TreeRead(val targetId: Int = 0) {
        val elements = ArrayList<BgNode>()
        var visited = 0
        var complete = true
        var hasSecrets = false
        var labelsTruncated = false
        var target: AccessibilityNodeInfo? = null
    }

    private fun readTree(displayId: Int, pkg: String): TreeRead {
        val vd = display ?: throw Fault("displayMissing")
        val conn = TreeConnection(context, pkg)
        val automation = try { conn.open(vd.display) } catch (_: Throwable) { throw Fault("treeUnavailable") }
        try { return readTree(automation, displayId, pkg) } finally { conn.close() }
    }

    private fun readTree(automation: UiAutomation, displayId: Int, pkg: String, targetId: Int = 0): TreeRead {
        runCatching { invoke(automation, "clearCache") }
        @Suppress("UNCHECKED_CAST")
        val all = invoke(automation, "getWindowsOnAllDisplays") as? SparseArray<List<AccessibilityWindowInfo>>
            ?: throw Fault("treeUnavailable")
        val appWindow = all.get(displayId).orEmpty().firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        } ?: throw Fault("treeUnavailable")
        val root = appWindow.root ?: throw Fault("treeUnavailable")
        val tree = TreeRead(targetId)
        try {
            if (root.packageName?.toString() != pkg) throw Fault("displayTargetChanged")
            walk(root, tree, 0, 600, "0", 0, -1)
            assignRegions(tree.elements)
            return tree
        } catch (e: Throwable) {
            tree.target?.recycle()
            throw e
        } finally { root.recycle() }
    }

    private fun walk(node: AccessibilityNodeInfo?, tree: TreeRead, indent: Int, budget: Int, path: String, parentId: Int, inheritedRow: Int) {
        if (node == null) return
        if (tree.visited >= budget) { tree.complete = false; return }
        tree.visited++
        if (node.isVisibleToUser && isSecret(node)) tree.hasSecrets = true
        val actionable = node.isClickable || node.isLongClickable || node.isEditable || node.isCheckable || node.isScrollable
        val text = bestText(node)
        if (text.replace('\n', ' ').trim().length > BackgroundTree.LINE_CAP) tree.labelsTruncated = true
        val emit = actionable || (text.isNotEmpty() && node.isVisibleToUser)
        var childIndent = indent
        var childParent = parentId
        val row = node.collectionItemInfo?.rowIndex ?: inheritedRow
        if (emit && node.isVisibleToUser) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            tree.elements.add(toElement(node, text, rect, indent, path, parentId, row))
            childParent = tree.elements.size
            // Retain the exact node assigned this screen id, not whichever editor has focus.
            if (tree.elements.size == tree.targetId) tree.target = AccessibilityNodeInfo.obtain(node)
            if (node.isScrollable) childIndent = indent + 1
        }
        for (i in 0 until node.childCount) {
            if (tree.visited >= budget) { tree.complete = false; break }
            val child = node.getChild(i)
            if (child == null) tree.complete = false
            try { walk(child, tree, childIndent, budget, "$path/$i", childParent, if (node.isScrollable) -1 else row) } finally { child?.recycle() }
        }
    }

    private fun toElement(node: AccessibilityNodeInfo, text: String, rect: Rect, indent: Int, path: String = "", parentId: Int = 0, row: Int = -1): BgNode {
        val cls = node.className?.toString().orEmpty()
        val password = node.isPassword || secretInput(node)
        val hint = node.hintText?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val secret = isSecret(node)
        val role = role(node, cls)
        val filled = !node.isShowingHintText && !node.text.isNullOrBlank()
        val label = BackgroundTree.label(text, description.ifBlank { hint }, filled, password, secret)
        val states = ArrayList<String>()
        // l10n:keep privileged screen protocol
        if (node.isFocused) states.add("已聚焦")
        // l10n:keep privileged screen protocol
        if (node.isSelected) states.add("已选")
        // l10n:keep privileged screen protocol
        if (node.isCheckable) states.add(if (node.isChecked) "已勾选" else "未勾选")
        // l10n:keep privileged screen protocol
        if (!node.isEnabled) states.add("不可用")
        // l10n:keep privileged screen protocol
        if (node.isScrollable) states.add("可滚动")
        // l10n:keep privileged screen protocol
        if (password) states.add("密码·不可代填")
        var flags = 0
        if (node.isPassword) flags = flags or Proto.FLAG_PASSWORD
        if (secret) flags = flags or Proto.FLAG_SECRET
        if (node.isEditable) flags = flags or Proto.FLAG_EDITABLE
        if (node.isClickable) flags = flags or Proto.FLAG_CLICKABLE
        if (node.isLongClickable) flags = flags or Proto.FLAG_LONGCLICK
        if (node.isScrollable) flags = flags or Proto.FLAG_SCROLLABLE
        if (node.isFocused) flags = flags or Proto.FLAG_FOCUSED
        val viewId = node.viewIdResourceName
        if (SensitiveWords.confirmHit(label, viewId) != null) flags = flags or Proto.FLAG_SENSITIVE
        val container = BackgroundRepairIdentity.digest(node.windowId, node.packageName, path, viewId, cls, rect.flattenToString())
        val identity = BackgroundRepairIdentity.digest(container, text, node.contentDescription, node.hintText, flags, node.isEnabled, node.isChecked)
        return BgNode(role, label, states, flags, indent, rect, node.packageName?.toString(), viewId,
            parentId, path, node.windowId, cls, identity, container, collectionEdge(node, true), collectionEdge(node, false), row,
            node.collectionInfo?.takeIf { it.columnCount <= 1 }?.rowCount ?: -1)
    }

    private fun collectionEdge(node: AccessibilityNodeInfo, start: Boolean): Boolean {
        val count = node.collectionInfo?.rowCount ?: return false
        if (!node.isScrollable || count <= 0) return false
        fun visibleEdge(child: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 12 || !child.isVisibleToUser) return false
            val info = child.collectionItemInfo
            if (info != null && info.rowIndex >= 0 &&
                (if (start) info.rowIndex == 0 else info.rowIndex + info.rowSpan.coerceAtLeast(1) >= count)) return true
            for (i in 0 until child.childCount) {
                val nested = child.getChild(i) ?: continue
                try { if (visibleEdge(nested, depth + 1)) return true } finally { nested.recycle() }
            }
            return false
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { if (visibleEdge(child, 0)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun role(node: AccessibilityNodeInfo, cls: String): String = when {
        // l10n:keep privileged screen protocol
        node.isEditable -> "输入框"
        // l10n:keep privileged screen protocol
        cls.endsWith("Switch") || cls.contains("Switch") -> "开关"
        // l10n:keep privileged screen protocol
        node.isCheckable -> "勾选框"
        // l10n:keep privileged screen protocol
        cls.contains("Button") || node.isClickable && node.text != null -> "按钮"
        // l10n:keep privileged screen protocol
        cls.contains("ImageView") && node.isClickable -> "图片"
        // l10n:keep privileged screen protocol
        cls.contains("RecyclerView") || cls.contains("ListView") -> "列表"
        // l10n:keep privileged screen protocol
        node.isClickable -> "按钮"
        // l10n:keep privileged screen protocol
        else -> "文本"
    }

    private fun isSecret(node: AccessibilityNodeInfo): Boolean = node.isPassword || secretInput(node) ||
        SensitiveWords.secretHit(
            node.hintText?.toString(), node.contentDescription?.toString(),
            // Resource ids classify inputs: a sendOtp button requests a code, not a secret value.
            // Keep hint/description masking for non-editable secret displays.
            if (node.isEditable) node.viewIdResourceName else null,
        ) != null

    private fun secretInput(node: AccessibilityNodeInfo): Boolean {
        val klass = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return (klass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun bestText(node: AccessibilityNodeInfo): String {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (node.isEditable) node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private fun assignRegions(out: List<BgNode>) {
        if (out.isEmpty()) return
        val top = height / 3; val bottom = height * 2 / 3
        for (e in out) {
            val cy = (e.bounds.top + e.bounds.bottom) / 2
            e.region = when {
                cy < top -> 0
                cy < bottom -> 1
                else -> 2
            }
        }
    }

    // ---- actions -----------------------------------------------------------

    private fun validateSnapshot(request: Bundle) {
        val index = request.getInt(Proto.ELEMENT, 0).takeIf { it > 0 }
        if (!snapshots.accepts(request.getString(Proto.OWNER).orEmpty(), request.getString(Proto.SNAPSHOT),
                index, request.getString(Proto.FINGERPRINT))) throw Fault("staleSnapshot")
    }

    private fun <T> withTarget(pkg: String, request: Bundle, action: (AccessibilityNodeInfo, BgNode, UiAutomation) -> T): T {
        validateSnapshot(request)
        val index = request.getInt(Proto.ELEMENT, -1)
        if (index < 1) throw Fault("invalidArguments")
        val vd = display ?: throw Fault("displayMissing")
        val conn = TreeConnection(context, pkg)
        val automation = try { conn.open(vd.display) } catch (_: Throwable) { throw Fault("treeUnavailable") }
        try {
            val tree = readTree(automation, displayId(), pkg, index)
            val node = tree.target ?: throw Fault("elementMissing")
            try {
                val entry = tree.elements.getOrNull(index - 1) ?: throw Fault("elementMissing")
                if (!tree.complete || entry.fingerprint != request.getString(Proto.FINGERPRINT)) throw Fault("staleSnapshot")
                if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled) throw Fault("staleSnapshot")
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val refreshed = toElement(node, bestText(node), rect, entry.indent, entry.path, entry.parentId, entry.rowIndex)
                if (refreshed.fingerprint != entry.fingerprint) throw Fault("staleSnapshot")
                return action(node, entry, automation)
            } finally { node.recycle() }
        } finally { conn.close() }
    }

    private fun tap(pkg: String, request: Bundle) = withTarget(pkg, request) { node, _, _ ->
        if (isSecret(node) && !request.getBoolean(Proto.ALLOW_SECRET)) throw Fault("textRequiresManual")
        snapshots.clear()
        val action = if (request.getBoolean(Proto.LONG)) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        // Do not dispatch a coordinate fallback after an unknown node-action outcome.
        if (!node.performAction(action)) throw Fault("actionRejected")
    }

    private fun scroll(pkg: String, request: Bundle, response: Bundle) = withTarget(pkg, request) { node, entry, automation ->
        if (!node.isScrollable) throw Fault("notScrollable")
        val direction = request.getString(Proto.DIR) ?: "down"
        val action = when (direction) {
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else -> throw Fault("invalidArguments")
        }
        val actions = node.actionList.map { it.id }
        val selected = if (action in actions) action else when (direction) {
            "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (selected !in actions) throw Fault("scrollUnsupported")
        val before = readTree(automation, displayId(), pkg).elements.filter { it.path.startsWith(entry.path + "/") }
            .map { it.fingerprint }
        snapshots.clear()
        val accepted = node.performAction(selected)
        response.putBoolean(Proto.ACCEPTED, accepted)
        if (!accepted) throw Fault("scrollRejected")
        var changed = false
        repeat(8) {
            if (!changed) {
                sleepChecked(150)
                val after = readTree(automation, displayId(), pkg).elements.filter { it.path.startsWith(entry.path + "/") }
                    .map { it.fingerprint }
                changed = after != before
            }
        }
        response.putBoolean(Proto.SCROLLED, changed)
        // Absence of movement does not prove end-of-list (loading/identical rows are possible).
        response.putBoolean(Proto.AT_END, false)
        if (!changed) response.putString("status", "unconfirmed")
    }

    private fun present(pkg: String, response: Bundle) {
        val source = displayId()
        val task = activities.getRunningTasks(100).firstOrNull {
            taskDisplayIdField?.getInt(it) == source && (it.topActivity?.packageName == pkg || it.baseActivity?.packageName == pkg)
        } ?: throw Fault("presentTaskMissing")
        val rootId = runCatching { task.javaClass.getField("rootTaskId").getInt(task) }.getOrDefault(task.taskId)
        val type = Class.forName("android.app.ActivityTaskManager")
        val manager = type.getMethod("getService").invoke(null)
        val method = Class.forName("android.app.IActivityTaskManager").methods.firstOrNull {
            it.name == "moveRootTaskToDisplay" && it.parameterCount == 2
        } ?: throw Fault("presentUnsupported")
        snapshots.clear()
        method.invoke(manager, rootId, Display.DEFAULT_DISPLAY)
        val deadline = SystemClock.uptimeMillis() + 4000
        while (true) {
            val moved = activities.getRunningTasks(100).firstOrNull { taskDisplayIdField?.getInt(it) == Display.DEFAULT_DISPLAY }
            if (moved != null && moved.taskId == task.taskId && moved.topActivity?.packageName == pkg) {
                response.putBoolean(Proto.PRESENTED, true)
                response.putInt(Proto.TASK_ID, task.taskId)
                response.putInt(Proto.DISPLAY_ID, Display.DEFAULT_DISPLAY)
                response.putString(Proto.FG_PKG, pkg)
                return
            }
            if (SystemClock.uptimeMillis() >= deadline) throw Fault("presentUnconfirmed")
            sleepChecked(100)
        }
    }

    private fun globalKey(key: String?) {
        val code = KEYS[key] ?: throw Fault("invalidArguments")
        val now = SystemClock.uptimeMillis()
        injectKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        sleepChecked(4)
        injectKey(KeyEvent(SystemClock.uptimeMillis(), now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun typeText(pkg: String, request: Bundle, response: Bundle) {
        val text = request.getString(Proto.TEXT).orEmpty()
        val index = request.getInt(Proto.ELEMENT, -1)
        if (index < 1 || text.length > 2000 || '\u0000' in text) throw Fault("invalidArguments")
        validateSnapshot(request)
        val vd = display ?: throw Fault("displayMissing")
        val conn = TreeConnection(context, pkg)
        val automation = try { conn.open(vd.display) } catch (_: Throwable) { throw Fault("textConnectionUnavailable") }
        try {
            val tree = readTree(automation, vd.display.displayId, pkg, index)
            val entry = tree.elements.getOrNull(index - 1) ?: throw Fault("elementMissing")
            if (!tree.complete || entry.fingerprint != request.getString(Proto.FINGERPRINT)) throw Fault("staleSnapshot")
            val node = tree.target ?: throw Fault("elementMissing")
            try {
                if (!node.refresh()) throw Fault("textTargetChanged")
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val current = toElement(node, bestText(node), rect, entry.indent, entry.path, entry.parentId, entry.rowIndex)
                if (current.fingerprint != entry.fingerprint) throw Fault("staleSnapshot")
                snapshots.clear()
                if (isSecret(node) && !request.getBoolean(Proto.ALLOW_SECRET, false)) throw Fault("textRequiresManual")
                if (!node.isEditable || node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })
                    throw Fault("textUnsupported")
                val submit = request.getBoolean(Proto.SUBMIT)
                if (submit) {
                    if (!node.isFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) throw Fault("textFocusMissing")
                    if (!node.refresh() || !node.isFocused) throw Fault("textTargetChanged")
                }
                // type(id, text) replaces the entire value, regardless of the old selection.
                val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                })
                response.putBoolean(Proto.ACCEPTED, accepted)
                if (!accepted) throw Fault("inputRejected")
                val deadline = SystemClock.uptimeMillis() + 1500
                while (true) {
                    runCatching { invoke(automation, "clearCache") }
                    if (!node.refresh()) throw Fault("textTargetChanged")
                    val now = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
                    if (now == text) break
                    if (SystemClock.uptimeMillis() >= deadline) throw Fault("textReadbackMismatch")
                    sleepChecked(50)
                }
                response.putString(Proto.READBACK, if (isSecret(node)) "«敏感输入已遮蔽»" else BackgroundTree.cap(text))
                response.putString("status", if (submit) "dispatched" else "completed")
                response.putBoolean("verified", !submit)
                response.putBoolean("written", true)
                response.putBoolean("submitted", false)
                if (submit) {
                    if (!node.isFocused) throw Fault("textTargetChanged")
                    globalKey("enter")
                    response.putBoolean("submitted", true)
                }
            } finally { node.recycle() }
        } finally { conn.close() }
    }

    // ---- injection shims (hidden APIs via reflection) ----------------------

    private fun injectTap(x: Int, y: Int, long: Boolean) {
        val down = SystemClock.uptimeMillis()
        sendMotion(MotionEvent.ACTION_DOWN, x, y, down)
        sleepChecked(if (long) 650 else 60)
        sendMotion(MotionEvent.ACTION_UP, x, y, down)
    }

    private fun sendMotion(action: Int, x: Int, y: Int, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { inject(event) } finally { event.recycle() }
    }

    private fun injectKey(src: KeyEvent) {
        val event = KeyEvent(src.downTime, src.eventTime, src.action, src.keyCode, 0, src.metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, src.scanCode, src.flags, InputDevice.SOURCE_KEYBOARD)
        inject(event)
    }

    // Runs in the root- or shell-uid app_process, where the app hidden-API policy
    // that lint models doesn't apply.
    @SuppressLint("BlockedPrivateApi")
    private fun inject(event: InputEvent) {
        val id = display?.display?.displayId ?: throw Fault("displayMissing")
        InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(event, id)
        val type = Class.forName("android.hardware.input.InputManager")
        // Android 17/API 37 dropped InputManager.getInstance(); the class is a plain Context
        // service there, so prefer the service lookup and keep the factory for older releases.
        val manager = privileged.getSystemService(Context.INPUT_SERVICE)
            ?.takeIf { type.isInstance(it) }
            ?: type.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        val method = type.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        // Prefer WAIT_FOR_FINISH (2) for a confirmed delivery; some ROMs and the emulator only
        // accept ASYNC (0). Either way the action is verified by re-observing the screen, never by
        // replaying it — so an async accept is safe here.
        val accepted = method.invoke(manager, event, 2) == true || method.invoke(manager, event, 0) == true
        if (!accepted) throw Fault("inputRejected")
    }

    private fun setImePolicyHide(displayId: Int) {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "window") as IBinder
        val wm = Class.forName("android.view.IWindowManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        // DISPLAY_IME_POLICY_HIDE == 2
        wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(wm, displayId, 2)
    }

    /**
     * `pm grant` of WRITE_SECURE_SETTINGS to this app, through the binder `pm` itself uses:
     * IPackageManager up to Android 10, IPermissionManager from 11 (Android 15 added a device id
     * before the user). Fixed permission, fixed package.
     */
    @SuppressLint("PrivateApi")
    private fun grantSecureSettings(userId: Int) {
        val (service, type) = if (Build.VERSION.SDK_INT >= 30) "permissionmgr" to "android.permission.IPermissionManager"
            else "package" to "android.content.pm.IPackageManager"
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, service) as IBinder
        val manager = Class.forName("$type\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val grant = manager.javaClass.methods.first { it.name == "grantRuntimePermission" }
        val permission = SecureSettingsGrant.PERMISSION
        val args = when {
            grant.parameterTypes.size == 3 -> arrayOf<Any>(Proto.APP_PACKAGE, permission, userId)
            // VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT, or Context.DEVICE_ID_DEFAULT.
            grant.parameterTypes[2] == String::class.java -> arrayOf<Any>(Proto.APP_PACKAGE, permission, "default:0", userId)
            else -> arrayOf<Any>(Proto.APP_PACKAGE, permission, 0, userId)
        }
        try {
            grant.invoke(manager, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun invoke(target: Any, method: String): Any? =
        UiAutomation::class.java.getMethod(method).invoke(target)

    private fun sleepChecked(ms: Long) {
        if (ms <= 0) return
        if (released.get()) throw Fault("cancelled")
        try { Thread.sleep(ms) } catch (_: InterruptedException) { throw Fault("cancelled") }
    }

    /**
     * A short-lived, node-only UiAutomation bound to the owned display (after Phase's
     * DisplayTextConnection): FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, so it does not evict the
     * user's other services; a hand-rolled IUiAutomationConnection so disconnect never restores
     * the main screen's rotation; only the node connection is exposed (no shell/input/permission).
     */
    private class TreeConnection(private val context: Context, private val target: String) {
        private val clientType = Class.forName("android.accessibilityservice.IAccessibilityServiceClient")
        private val managerType = Class.forName("android.view.accessibility.IAccessibilityManager")
        private val manager = Class.forName("android.view.accessibility.IAccessibilityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null,
                Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                    .invoke(null, Context.ACCESSIBILITY_SERVICE))
        private val token = Binder()
        private var client: Any? = null
        private var automation: UiAutomation? = null

        fun open(display: Display): UiAutomation {
            if (Build.VERSION.SDK_INT < 34) throw Fault("textConnectionUnavailable")
            val connectionType = Class.forName("android.app.IUiAutomationConnection")
            val connection = Proxy.newProxyInstance(connectionType.classLoader, arrayOf(connectionType)) { proxy, method, args ->
                when (method.name) {
                    "asBinder" -> token
                    "connect" -> { runCatching { register(args!![0]!!) }; null }
                    "disconnect" -> { unregister(); null }
                    "toString" -> "DshTreeConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> throw UnsupportedOperationException("node only")
                }
            }
            val value = UiAutomation::class.java.getConstructor(Context::class.java, connectionType)
                .newInstance(context.createDisplayContext(display), connection) as UiAutomation
            automation = value
            UiAutomation::class.java.getMethod("connectWithTimeout", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                .invoke(value, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, 3000L)
            return value
        }

        private fun register(value: Any) {
            val info = AccessibilityServiceInfo().apply {
                packageNames = arrayOf(target)
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            AccessibilityServiceInfo::class.java.getMethod("setCapabilities", Int::class.javaPrimitiveType)
                .invoke(info, AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)
            val userId = Class.forName("android.os.UserHandle").getMethod("getUserId", Int::class.javaPrimitiveType)
                .invoke(null, context.applicationInfo.uid)
            managerType.getMethod("registerUiTestAutomationService", IBinder::class.java, clientType,
                AccessibilityServiceInfo::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(manager, token, value, info, userId, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            client = value
        }

        private fun unregister() {
            val value = client ?: return
            runCatching { managerType.getMethod("unregisterUiTestAutomationService", clientType).invoke(manager, value) }
            client = null
        }

        fun close() {
            runCatching { automation?.let { UiAutomation::class.java.getMethod("disconnect").invoke(it) } }
            automation = null
            unregister()
        }
    }

    companion object {
        private val PACKAGE = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")
        private val FLAG_NAMES = listOf(
            "PUBLIC", "OWN_CONTENT_ONLY", "SUPPORTS_TOUCH", "DESTROY_CONTENT_ON_REMOVAL",
            "TRUSTED", "OWN_FOCUS", "STEAL_TOP_FOCUS_DISABLED")
        private val KEYS = mapOf(
            "back" to KeyEvent.KEYCODE_BACK, "home" to KeyEvent.KEYCODE_HOME,
            "enter" to KeyEvent.KEYCODE_ENTER, "delete" to KeyEvent.KEYCODE_DEL,
            "tab" to KeyEvent.KEYCODE_TAB, "escape" to KeyEvent.KEYCODE_ESCAPE,
            "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}

/** Live-tree node captured during a read; carries what the driver needs to build a TargetInfo. */
class BgNode(
    val role: String,
    val label: String,
    val states: List<String>,
    val flags: Int,
    val indent: Int,
    val bounds: android.graphics.Rect,
    val packageName: String?,
    val viewId: String? = null,
    val parentId: Int = 0,
    val path: String = "",
    val windowId: Int = -1,
    val className: String = "",
    val fingerprint: String = "",
    val containerIdentity: String = "",
    val atListStart: Boolean = false,
    val atListEnd: Boolean = false,
    val rowIndex: Int = -1,
    val rowCount: Int = -1,
) {
    var region: Int = 1
}
