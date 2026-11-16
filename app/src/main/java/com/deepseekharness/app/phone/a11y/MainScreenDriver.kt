// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's permission. See THIRD_PARTY_NOTICES.md.
// Local changes: the screen text format instead of JSON; fingerprints re-checked against a fresh
// capture; password fields marked rather than failing the screen; collect, open, numbered
// screenshots, tap_xy; blocking calls with time budgets.

package com.deepseekharness.app.phone.a11y

import com.deepseekharness.app.i18n.AppStrings
import com.deepseekharness.app.R
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.deepseekharness.app.phone.ActionResult
import com.deepseekharness.app.phone.Availability
import com.deepseekharness.app.phone.CollectResult
import com.deepseekharness.app.phone.PhoneAction
import com.deepseekharness.app.phone.PhoneDriver
import com.deepseekharness.app.phone.PhoneRegistry
import com.deepseekharness.app.phone.PhoneUnlocks
import com.deepseekharness.app.phone.PhoneScreen
import com.deepseekharness.app.phone.SafetyRules
import com.deepseekharness.app.phone.ScreenSnapshot
import com.deepseekharness.app.phone.TargetInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The main screen, read and driven through [PhoneA11yService]. Calls block (bridge worker
 * thread); observe stays under ~5 s, actions under ~15 s, collect under ~100 s. Only the
 * latest snapshot's ids are valid; every action re-reads the tree and refuses when the
 * element's fingerprint no longer matches.
 */
internal class MainScreenDriver(private val service: PhoneA11yService, private val overlay: ControlOverlay) : PhoneDriver {
    override val screen = PhoneScreen.MAIN

    private val ownPackage: String = service.packageName

    /** 解除限制, read at each check: the person may lift or restore a rule between calls. */
    private val lifted: PhoneUnlocks get() = PhoneRegistry.unlocks()
    private val clock get() = service.clock
    private val actuator = Actuator(service, overlay)
    private val shooter = ScreenShooter(service, overlay)
    private val power = service.getSystemService(PowerManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val labels = ConcurrentHashMap<String, String>()

    /** Wall-clock form of a screenshot's system timestamp (only touched inside [lock]). */
    private val shotClock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val lock = Any()
    private val snapshotLock = Any()
    @Volatile private var latest: Snapshot? = null

    /** Bumped by [abort] (stop, screen off): loops and waits started before it give up. */
    private val abortGeneration = java.util.concurrent.atomic.AtomicLong()
    private val abortGen: Long get() = abortGeneration.get()

    private class Snapshot(
        val id: String,
        val generation: Long,
        val screen: FormattedScreen,
        val packageName: String?,
        val appLabel: String?,
        val protectedPage: Boolean,
        val ownApp: Boolean,
        val mainWindowId: Int?,
        /** The front window hides its content from us (data-sensitive page): reported as secure. */
        val hidden: Boolean,
        /** The window is there but nothing under it was readable (filtered subtree, page still drawing). */
        val unreadable: Boolean,
        val keyboardShown: Boolean,
        /** The main window's bounds: the IME showing / hiding moves them. */
        val mainWindowBounds: Box?,
        /** Notes already shown on this app's previous screen (not repeated). */
        val noteKeys: Set<String>,
    ) {
        @Volatile var image: ImageGeometry? = null
        val fingerprints: Set<String> by lazy { screen.elements.map { it.fingerprint }.toSet() }
    }

    fun close() {
        abort()
        actuator.close()
        shooter.close()
    }

    fun abort() = synchronized(snapshotLock) {
        abortGeneration.incrementAndGet()
        latest = null
    }

    override fun invalidate() = abort()

    override fun isCurrent(screenId: String): Boolean {
        val snap = latest
        return MainRepair.isCurrent(screenId, snap?.id, snap?.generation, abortGen) && availability().ok
    }

    // ---- PhoneDriver ------------------------------------------------------------------------------

    override fun availability(): Availability = when {
        PhoneA11yService.instance == null ->
            // l10n:keep agent screen result
            Availability(false, "DSH 的无障碍服务没有连接。请用户到 系统设置 → 无障碍 里重新打开「DSH 手机操控」。", AppStrings.get(service, R.string.phone_availability_disconnected))
        // l10n:keep agent screen result
        !power.isInteractive -> Availability(false, "屏幕已关闭。DSH 只在亮屏、解锁时操作手机，请用户点亮并解锁。", AppStrings.get(service, R.string.phone_availability_screen_off))
        // l10n:keep agent screen result
        keyguard.isKeyguardLocked -> Availability(false, "手机已锁屏。请用户解锁后再继续。", AppStrings.get(service, R.string.phone_availability_locked))
        else -> Availability(true)
    }

    override fun foregroundPackage(): String? = TreeCapture.foregroundPackage(service, screenBox())

    override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot = synchronized(lock) {
        val start = SystemClock.uptimeMillis()
        val generation = abortGen
        availability().let { if (!it.ok) return blank(it.reason!!) }
        val cap = capture()
        try {
            val snap = publish(cap, detail, generation)
            val text = StringBuilder(snap.screen.text)
            var bytes: ByteArray? = null
            var mime: String? = null
            var secure = false
            if (image) {
                val left = OBSERVE_MS - (SystemClock.uptimeMillis() - start)
                when {
                    // l10n:keep agent screen result
                    snap.ownApp && !lifted.ownApp -> text.append("\n提示：DSH 自己的界面不截图。")
                    // l10n:keep agent screen result
                    ScreenFormat.index(cap.windows).any { it.fullText.contains("«银行卡") || it.fullText.contains("«身份证") } ->
                        // l10n:keep agent screen result
                        text.append("\n提示：本屏有银行卡号或身份证号，这次没有附截图。")
                    // l10n:keep agent screen result
                    left < 900 -> text.append("\n提示：读屏用时过长，这次没有附截图。")
                    else -> {
                        val window = if (cap.windows.size == 1) cap.windows[0] else null
                        val secrets = ScreenShooter.sensitiveBounds(cap.windows)
                        val shot = shooter.shoot(window, cap.screen, snap.screen.elements, secrets, minOf(left - 300, 3_000))
                        secure = shot.secure
                        if (shot.bytes != null) {
                            bytes = shot.bytes
                            mime = shot.mime
                            snap.image = shot.geometry
                            // l10n:keep agent screen result
                            text.append("\n截图上的数字是元素编号；tap_xy 用截图像素（${shot.geometry!!.width}×${shot.geometry.height}）。")
                            // l10n:keep agent screen result
                            shot.takenAtMillis?.let { text.append("\n截图时间：${shotClock.format(Date(it))}（系统截图时间戳，设备时钟）。") }
                        // l10n:keep agent screen result
                        } else text.append("\n提示：${shot.error ?: "截图失败"}。")
                    }
                }
            }
            ScreenSnapshot(snap.id, text.toString(), snap.appLabel, snap.packageName, secure || snap.hidden, snap.screen.poorTree, bytes, mime)
        } finally { cap.recycle() }
    }

    override fun target(screenId: String, elementId: Int): TargetInfo? = synchronized(lock) {
        val snap = latest ?: return null
        if (!isCurrent(screenId)) return null
        val el = snap.screen.element(elementId) ?: return null
        if (!availability().ok) return null
        val cap = capture()
        try { if (locate(cap, el) == null) return null } finally { cap.recycle() }
        if (!isCurrent(screenId)) return null
        info(screenId, el)
    }

    /** [el] as the core and the gate see it. */
    private fun info(screenId: String, el: ScreenElement) = TargetInfo(
        screenId = screenId, elementId = el.id, role = el.role, label = el.label,
        packageName = el.packageName, appLabel = label(el.packageName), isPassword = el.password,
        isSecretField = el.secret, isEditable = el.editable, viewId = el.viewId,
    )

    override fun perform(action: PhoneAction): ActionResult {
        // l10n:keep agent screen result
        if (Looper.myLooper() == Looper.getMainLooper()) return ActionResult(false, "内部错误：手机动作不能在主线程执行。")
        synchronized(lock) {
            availability().let { if (!it.ok) return ActionResult(false, it.reason!!) }
            // l10n:keep agent screen result
            if (overlay.paused) return ActionResult(false, "用户在悬浮条上按了暂停。等用户点「继续」后再操作，不要换别的方式绕过。")
            // l10n:keep agent screen result
            if (!awaitInputIdle()) return ActionResult(false, "用户正在悬浮条上输入插话，稍等再操作。")
            val gen = abortGen
            if (action !is PhoneAction.Key && !isCurrent(action.screenId)) {
                return ActionResult(false, "屏幕编号 ${action.screenId} 已过期，没有执行；请先 screen。")
            }
            return when (action) {
                is PhoneAction.Key -> key(action, gen)
                is PhoneAction.Tap -> tap(action, gen)
                is PhoneAction.Type -> type(action, gen)
                is PhoneAction.Scroll -> scroll(action, gen)
                is PhoneAction.TapXY -> tapXY(action, gen)
            }
        }
    }

    override fun open(packageName: String): ActionResult {
        // l10n:keep agent screen result
        if (Looper.myLooper() == Looper.getMainLooper()) return ActionResult(false, "内部错误：手机动作不能在主线程执行。")
        synchronized(lock) {
            availability().let { if (!it.ok) return ActionResult(false, it.reason!!) }
            // l10n:keep agent screen result
            if (packageName == ownPackage && !lifted.ownApp) return ActionResult(false, "DSH 自己不需要打开。")
            val name = label(packageName) ?: packageName
            val intent = service.packageManager.getLaunchIntentForPackage(packageName)
                // l10n:keep agent screen result
                ?: return ActionResult(false, "没找到「$name」，或它没有可以打开的界面。可以先用 apps 查一下包名。")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val gen = abortGen
            try { service.startActivity(intent) } catch (e: Exception) {
                // l10n:keep agent screen result
                return ActionResult(false, "打开「$name」失败：${e.message ?: e.javaClass.simpleName}")
            }
            val deadline = SystemClock.uptimeMillis() + 6_000
            var front: String? = null
            while (SystemClock.uptimeMillis() < deadline && !cancelled(gen)) {
                front = foregroundPackage()
                if (front == packageName) break
                clock.pause(150) { cancelled(gen) }
            }
            if (front == packageName) clock.settle(clock.count, 800, 400, 3_000) { cancelled(gen) }
            val snap = if (front == packageName && availability().ok) {
                val cap = settledCapture(null, gen)
                try { toSnapshot(publish(cap, false, gen)) } finally { cap.recycle() }
            } else if (!cancelled(gen)) observeQuiet(gen) else null
            // l10n:keep agent screen result
            return if (front == packageName) ActionResult(true, "已打开「$name」。", snap)
            // l10n:keep agent screen result
            else ActionResult(false, "已请求打开「$name」，但 6 秒内它没有到前台（现在是「${label(front) ?: front ?: "未知"}」）。", snap)
        }
    }

    override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return CollectResult(emptyList(), 0, "unconfirmed", null)
        synchronized(lock) {
            availability().let { if (!it.ok) return CollectResult(emptyList(), 0, "unavailable", null) }
            val snap = latest
            if (snap == null || !isCurrent(screenId)) {
                return CollectResult(emptyList(), 0, "staleSnapshot", observeQuiet())
            }
            if (snap.ownApp && !lifted.ownApp) return CollectResult(emptyList(), 0, "denied", null)
            var el = snap.screen.element(listElementId)
                ?: return CollectResult(emptyList(), 0, "listLost", null)
            if (el.kind != ElementKind.LIST) {
                el = el.listId?.let { snap.screen.element(it) }
                    ?: return CollectResult(emptyList(), 0, "listLost", null)
            }
            val pages = maxPages.coerceIn(1, 30)
            val wanted = until?.trim()?.takeIf { it.isNotEmpty() }
            val gen = snap.generation
            val deadline = SystemClock.uptimeMillis() + COLLECT_MS
            val items = CollectedItems()
            var stop = "unconfirmed"
            var read = 0
            var misses = 0
            var truncated = false
            var startedAtBeginning = false
            val direction = if (el.horizontal) PhoneAction.Direction.RIGHT else PhoneAction.Direction.DOWN
            var lastBounds = el.bounds
            while (true) {
                if (cancelled(gen)) { stop = "cancelled"; break }
                if (overlay.paused) { stop = "paused"; break }
                if (SystemClock.uptimeMillis() > deadline) { stop = "timeout"; truncated = true; break }
                val cap = capture()
                try {
                    if (cap.unreadableWindowTitle != null || cap.hiddenWindowTitle != null) {
                        stop = "unreadable"; break
                    }
                    truncated = truncated || cap.truncated
                    val list = ScreenFormat.followList(ScreenFormat.index(cap.windows), el.fingerprint, lastBounds)
                        ?: run { stop = "listLost"; null } ?: break
                    lastBounds = list.bounds
                    read++
                    var added = 0
                    var found = false
                    for (text in list.itemTexts) {
                        if (items.add(text)) added++
                        if (wanted != null && text.contains(wanted, ignoreCase = true)) found = true
                    }
                    if (found) { stop = "until"; break }
                    if (items.chars > COLLECT_CHARS) { stop = "outputLimit"; truncated = true; break }
                    val node = cap.native(list.ref) ?: run { stop = "listLost"; null } ?: break
                    if (read == 1) startedAtBeginning = collectionAtBeginning(node, el.horizontal)
                    val atEnd = collectionAtEnd(node, el.horizontal)
                    misses = if (read > 1 && added == 0) misses + 1 else 0
                    MainRepair.collectStop(misses, null, atEnd)?.let { stop = it }
                    if (stop == "end" || misses >= 2) break
                    if (read >= pages) { stop = "pageLimit"; truncated = true; break }
                    if (cancelled(gen)) { stop = "cancelled"; break }
                    if (overlay.paused) { stop = "paused"; break }
                    val before = clock.count
                    val out = actuator.scroll(node, el.scroll, el.horizontal, list.bounds, cap.screen, direction)
                    if (!out.accepted) { stop = "unconfirmed"; break }
                    clock.settle(before, 1_200, 350, 3_000) { cancelled(gen) }
                    if (misses > 0) clock.pause(800) { cancelled(gen) }
                } finally { cap.recycle() }
            }
            return CollectResult(items.list(), read, stop, if (!cancelled(gen)) observeQuiet(gen) else null,
                complete = stop == "end" && startedAtBeginning && !truncated,
                truncated = truncated || (stop == "end" && !startedAtBeginning))
        }
    }

    private fun collectionAtBeginning(node: AccessibilityNodeInfo, horizontal: Boolean): Boolean {
        val backward = setOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            if (horizontal) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id)
        if (node.collectionInfo == null || node.actionList.any { it.id in backward }) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val item = child.collectionItemInfo ?: continue
                if (child.isVisibleToUser && (if (horizontal) item.columnIndex else item.rowIndex) == 0) return true
            } finally { child.recycle() }
        }
        return false
    }

    /** Only finite collection metadata and no remaining forward action establish an end. */
    private fun collectionAtEnd(node: AccessibilityNodeInfo, horizontal: Boolean): Boolean {
        val collection = node.collectionInfo ?: return false
        val total = if (horizontal) collection.columnCount else collection.rowCount
        var lastVisible = -1
        var visited = 0
        fun visit(current: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12 || ++visited > 600) return
            current.collectionItemInfo?.let { item ->
                if (current.isVisibleToUser) lastVisible = maxOf(lastVisible,
                    if (horizontal) item.columnIndex + item.columnSpan - 1 else item.rowIndex + item.rowSpan - 1)
            }
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                try { if (!child.isScrollable) visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        visit(node, 0)
        val forward = setOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            if (horizontal) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            if (horizontal) AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id
            else AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id)
        return MainRepair.collectionAtEnd(total, lastVisible, node.actionList.any { it.id in forward }, collection.isHierarchical)
    }

    // ---- actions ----------------------------------------------------------------------------------

    private class Resolved(val snap: Snapshot, val el: ScreenElement, val cap: Capture, val node: AccessibilityNodeInfo, val found: IndexedElement)

    /** The element of the latest snapshot, re-verified against a fresh capture; or the refusal. */
    private fun resolve(screenId: String, elementId: Int, generation: Long): Any {
        if (cancelled(generation)) return ActionResult(false, "操作已停止，没有执行。")
        val snap = latest
        if (snap == null || !isCurrent(screenId)) {
            val now = observeQuiet(generation)
            // l10n:keep agent screen result
            val msg = if (snap == null) "还没有读过屏幕，先用 screen 看一下。"
            // l10n:keep agent screen result
            else "屏幕编号 $screenId 过期了（最新 ${now?.id ?: snap.id}），没有执行。按新屏幕的编号来。"
            return ActionResult(false, msg, now)
        }
        val el = snap.screen.element(elementId)
            // l10n:keep agent screen result
            ?: return ActionResult(false, "屏幕 $screenId 里没有编号 $elementId（共 ${snap.screen.elements.size} 项）。")
        val cap = capture()
        if (!isCurrent(screenId)) {
            cap.recycle()
            return ActionResult(false, "观察已失效，没有执行；请重新 screen。")
        }
        val hit = locate(cap, el)
        val node = hit?.let { cap.native(it.ref) }
        if (hit == null || node == null) {
            val now = try { publish(cap, false, generation).let { toSnapshot(it) } } finally { cap.recycle() }
            // l10n:keep agent screen result
            return ActionResult(false, "目标 [$elementId]「${short(el.label.ifEmpty { el.role })}」变了，没有执行；看新屏幕再选。", now)
        }
        return Resolved(snap, el, cap, node, hit)
    }

    private fun refuseWrite(r: Resolved): ActionResult? {
        val lifted = lifted
        return when {
            // l10n:keep agent screen result
            (r.el.packageName == ownPackage || r.snap.ownApp) && !lifted.ownApp -> ActionResult(false, "这是 DSH 自己的界面，不操作。")
            // l10n:keep agent screen result
            r.snap.protectedPage && !lifted.systemPages -> ActionResult(false, "这是系统权限 / 安全相关页面，DSH 不在这里点击或输入。需要的话请用 handoff 交给用户。")
            // l10n:keep agent screen result
            (r.el.password || r.el.secret) && !lifted.secretFields -> ActionResult(false, "这是密码 / 验证码一类的输入框，DSH 不替用户点或填。请用 handoff 让用户自己来。")
            else -> null
        }
    }

    private fun tap(a: PhoneAction.Tap, gen: Long): ActionResult {
        val res = resolve(a.screenId, a.elementId, gen)
        val r = res as? Resolved ?: return res as ActionResult
        try {
            refuseWrite(r)?.let { return it }
            if (r.el.kind == ElementKind.LIST) {
                // l10n:keep agent screen result
                return ActionResult(false, "[${a.elementId}] 是列表本身，不能点。看更多用 scroll，整理全部条目用 collect。")
            }
            if (cancelled(gen) || !isCurrent(a.screenId)) return ActionResult(false, "操作已停止，没有点击。")
            val before = clock.count
            val out = actuator.click(r.node, r.el.bounds, r.cap.screen, a.long)
            val what = short(r.el.label.ifEmpty { r.el.role })
            // l10n:keep agent screen result
            if (!out.accepted) return ActionResult(false, "系统没有接受这次${if (a.long) "长按" else "点击"}「$what」。", observeQuiet(gen))
            // l10n:keep agent screen result
            return after(before, r.snap, gen, "已${if (a.long) "长按" else "点击"}「$what」。")
        } finally { r.cap.recycle() }
    }

    private fun type(a: PhoneAction.Type, gen: Long): ActionResult {
        val res = resolve(a.screenId, a.elementId, gen)
        val r = res as? Resolved ?: return res as ActionResult
        try {
            refuseWrite(r)?.let { return it }
            if (r.el.kind != ElementKind.INPUT || !r.node.isEditable) {
                // l10n:keep agent screen result
                return ActionResult(false, "[${a.elementId}]「${short(r.el.label)}」不是输入框：先点它，等输入框出现再输入。")
            }
            // l10n:keep agent screen result
            if (r.node.isPassword && !lifted.secretFields) return ActionResult(false, "这是密码框，DSH 不代填。请用 handoff 让用户自己输入。")
            // l10n:keep agent screen result
            if (!MainRepair.textWithinLimit(a.text)) return ActionResult(false, "一次最多输入 2000 字。")
            if (cancelled(gen) || !isCurrent(a.screenId)) return ActionResult(false, "操作已停止，没有输入。", written = false, submitted = false)
            val before = clock.count
            if (!actuator.setText(r.node, a.text)) {
                // l10n:keep agent screen result
                return ActionResult(false, "这个输入框不接受代填（系统拒绝了输入）。可以先点一下它再试，或请用户来输入。", observeQuiet(gen))
            }
            val got = readBack(r.node, a.text, gen)
            // l10n:keep agent screen result
            val name = short(r.el.label.ifEmpty { "输入框" })
            if (!sameText(got, a.text)) {
                return ActionResult(false, readBackMismatch(got, info(r.snap.id, r.el)), settledSnapshot(before, gen),
                    status = "unconfirmed", written = true, submitted = false)
            }
            val submitted = a.submit && !cancelled(gen) && actuator.imeEnter(r.node)
            // Writing succeeded even if the separate IME submission was refused.
            val message = MainRepair.submitMessage(name, a.submit, submitted)
            val result = after(before, r.snap, gen, message)
            return ActionResult(result.ok, result.message, result.screen,
                status = if (cancelled(gen) || (a.submit && !submitted)) "unconfirmed" else if (submitted) "dispatched" else "completed",
                verified = !a.submit && !cancelled(gen), written = true, submitted = submitted)
        } finally { r.cap.recycle() }
    }

    private fun scroll(a: PhoneAction.Scroll, gen: Long): ActionResult {
        val snap = latest
        if (snap == null || !isCurrent(a.screenId)) {
            return ActionResult(false, "屏幕编号 ${a.screenId} 已过期，没有滚动；请先 screen。")
        }
        val dirWord = when (a.direction) {
            // l10n:keep agent screen result
            PhoneAction.Direction.DOWN -> "向下"; PhoneAction.Direction.UP -> "向上"
            // l10n:keep agent screen result
            PhoneAction.Direction.LEFT -> "向左"; PhoneAction.Direction.RIGHT -> "向右"
        }
        // The element to scroll: the given one (or its list), else the biggest list on screen.
        var targetId: Int? = a.elementId
        if (targetId != null) {
            val el = snap.screen.element(targetId)
                ?: return ActionResult(false, "屏幕 ${a.screenId} 没有编号 $targetId，没有滚动。")
            if (el.kind != ElementKind.LIST) targetId = el.listId ?: targetId
        } else {
            targetId = snap.screen.elements.filter { it.kind == ElementKind.LIST }.maxByOrNull { it.bounds.area }?.id
        }
        if (targetId == null) {
            // l10n:keep agent screen result
            if (snap.ownApp && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
            // l10n:keep agent screen result
            if (snap.unreadable) return ActionResult(false, "这一屏读不到内容（可能受敏感视图策略限制），不盲滑。请 handoff。", observeQuiet(gen))
            val screen = screenBox()
            if (cancelled(gen) || !isCurrent(a.screenId)) return ActionResult(false, "操作已停止，没有滚动。")
            val before = clock.count
            val out = actuator.scroll(null, 0, a.direction == PhoneAction.Direction.LEFT || a.direction == PhoneAction.Direction.RIGHT,
                Box(screen.left, screen.top + screen.height / 5, screen.right, screen.bottom - screen.height / 6), screen, a.direction)
            // l10n:keep agent screen result
            if (!out.accepted) return ActionResult(false, "没能滚动。", observeQuiet(gen))
            // l10n:keep agent screen result
            return after(before, snap, gen, "已${dirWord}滑动屏幕。", scrolled = true)
        }
        val res = resolve(a.screenId, targetId, gen)
        val r = res as? Resolved ?: return res as ActionResult
        try {
            // l10n:keep agent screen result
            if ((r.el.packageName == ownPackage || r.snap.ownApp) && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
            if (cancelled(gen) || !isCurrent(a.screenId)) return ActionResult(false, "操作已停止，没有滚动。")
            val before = clock.count
            val out = actuator.scroll(r.node, r.el.scroll, r.el.horizontal, r.el.bounds, r.cap.screen, a.direction)
            // l10n:keep agent screen result
            if (!out.accepted) return ActionResult(false, "没能${dirWord}滚动，可能已经到头了。", observeQuiet(gen))
            // l10n:keep agent screen result
            return after(before, r.snap, gen, "已${dirWord}滚动${if (r.el.kind == ElementKind.LIST) "列表 [${r.el.id}]" else ""}。", scrolled = true)
        } finally { r.cap.recycle() }
    }

    private fun key(a: PhoneAction.Key, gen: Long): ActionResult {
        val before = clock.count
        // l10n:keep agent screen result
        if (!actuator.global(a.key)) return ActionResult(false, "系统没有执行这个按键。")
        val what = when (a.key) {
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.BACK -> "已按返回键。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.HOME -> "已回到桌面。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.RECENTS -> "已打开最近任务。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.NOTIFICATIONS -> "已拉下通知栏。"
        }
        return after(before, latest, gen, what)
    }

    private fun tapXY(a: PhoneAction.TapXY, gen: Long): ActionResult {
        val snap = latest
        if (snap == null || !isCurrent(a.screenId)) {
            val now = observeQuiet(gen)
            // l10n:keep agent screen result
            return ActionResult(false, "屏幕编号 ${a.screenId} 已过期，坐标对不上了。请重新 screen 加 image 再点。", now)
        }
        // l10n:keep agent screen result
        val geometry = snap.image ?: return ActionResult(false, "屏幕 ${a.screenId} 没有截图。坐标点击要先用 screen 加 image 拿一张标号截图。")
        val point = geometry.toScreen(a.x, a.y)
            // l10n:keep agent screen result
            ?: return ActionResult(false, "坐标 (${a.x}, ${a.y}) 不在这张截图里（${geometry.width}×${geometry.height}）。")
        // l10n:keep agent screen result
        if (snap.ownApp && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
        // l10n:keep agent screen result
        if (snap.protectedPage && !lifted.systemPages) return ActionResult(false, "这是系统权限 / 安全相关页面，DSH 不在这里点击。需要的话请用 handoff 交给用户。")
        // l10n:keep agent screen result
        if (snap.unreadable) return ActionResult(false, "这一屏读不到内容（可能受敏感视图策略限制），不盲点。请 handoff。", observeQuiet(gen))
        val cap = capture()
        val same = try { cap.packageName == snap.packageName && cap.windows.lastOrNull()?.id == snap.mainWindowId } finally { cap.recycle() }
        // l10n:keep agent screen result
        if (!same) return ActionResult(false, "截图之后界面已经换了，没有点击。请重新看屏幕。", observeQuiet(gen))
        if (cancelled(gen) || !isCurrent(a.screenId)) return ActionResult(false, "操作已停止，没有点击。")
        val before = clock.count
        // l10n:keep agent screen result
        if (!actuator.tap(point.first, point.second)) return ActionResult(false, "系统没有接受这次点击。", observeQuiet(gen))
        // l10n:keep agent screen result
        return after(before, snap, gen, "已点击截图上的 (${a.x}, ${a.y})。")
    }

    /** Waits for the UI to settle, reads the new screen (`+` marks) and says whether anything changed. */
    private fun after(before: Long, previous: Snapshot?, gen: Long, message: String, scrolled: Boolean = false): ActionResult {
        clock.settle(before, 1_500, 300, 3_000) { cancelled(gen) }
        if (cancelled(gen)) return ActionResult(true, "$message（操作已停止，后续观察已取消。）", status = "unconfirmed")
        val cap = try { settledCapture(previous, gen) } catch (e: InterruptedException) { throw e } catch (e: Exception) {
            Log.w(TAG, "observation after an accepted action failed", e)
            // l10n:keep agent screen result
            return ActionResult(true, "$message（动作已执行，但没能读到新屏幕。）")
        }
        try {
            if (cancelled(gen)) return ActionResult(true, "$message（操作已停止，后续观察已取消。）", status = "unconfirmed")
            val snap = publish(cap, false, gen)
            val sameApp = previous != null && previous.packageName == snap.packageName
            // A different app in front is a change even when both trees happen to be empty.
            val changed = previous != null && (!sameApp || previous.fingerprints != snap.fingerprints)
            // The IME is part of the screen: `key back` that only closes the keyboard is a change,
            // even though no node moved. The main window's bounds move with it.
            val keyboard = when {
                !sameApp -> null
                previous!!.keyboardShown && !snap.keyboardShown -> ScreenVerdict.KEYBOARD_HIDDEN
                !previous!!.keyboardShown && snap.keyboardShown -> ScreenVerdict.KEYBOARD_SHOWN
                else -> null
            }
            val insets = sameApp && previous!!.mainWindowBounds != snap.mainWindowBounds
            // Both sides need readable elements for "unchanged" to mean anything.
            val comparable = previous != null && previous.screen.elements.isNotEmpty() &&
                snap.screen.elements.isNotEmpty() && !previous.unreadable && !snap.unreadable
            // l10n:keep agent screen result
            val note = ScreenVerdict.note(comparable = comparable, changed = changed || insets, keyboard = keyboard, scrolled = scrolled)
            // 上一屏没变的部分不再重复(见 ScreenDigest);不变时只回一行。
            val text = ScreenDigest.text(previous?.id, previous?.screen, snap.id, snap.screen)
            return ActionResult(true, message + note, toSnapshot(snap, text))
        } finally { cap.recycle() }
    }

    /**
     * A capture after an action. Right after a transition the new window may have no root yet
     * or an empty tree (Chrome, Settings sub-pages): retry a few times while that lasts.
     */
    private fun settledCapture(previous: Snapshot?, gen: Long): Capture {
        var cap = capture()
        var tries = 0
        while (tries++ < 4 && !cancelled(gen) && looksUnfinished(cap, previous)) {
            cap.recycle()
            clock.pause(400) { cancelled(gen) }
            cap = capture()
        }
        return cap
    }

    private fun looksUnfinished(cap: Capture, previous: Snapshot?): Boolean =
        cap.hiddenWindowTitle == null &&
            (cap.windows.isEmpty() || (ScreenFormat.textCount(cap.windows) < ScreenFormat.POOR_TREE && previous?.screen?.poorTree != true))

    private fun settledSnapshot(before: Long, gen: Long): ScreenSnapshot? {
        clock.settle(before, 800, 250, 2_000) { cancelled(gen) }
        return if (!cancelled(gen)) observeQuiet(gen) else null
    }

    /** Polls the field until it shows [expected] (or 1.5 s pass); returns what it shows. */
    private fun readBack(node: AccessibilityNodeInfo, expected: String, gen: Long): String? {
        val deadline = SystemClock.uptimeMillis() + 1_500
        var got: String? = null
        while (true) {
            node.refresh()
            got = if (node.isShowingHintTextCompat()) "" else node.text?.toString()
            if (sameText(got, expected) || SystemClock.uptimeMillis() > deadline || cancelled(gen)) return got
            clock.pause(100) { cancelled(gen) }
        }
    }

    private fun AccessibilityNodeInfo.isShowingHintTextCompat() = android.os.Build.VERSION.SDK_INT >= 26 && isShowingHintText

    /** Equal, or equal once the field's own formatting (spaces, dashes) is ignored. */
    private fun sameText(got: String?, expected: String): Boolean = MainRepair.sameText(got, expected)

    private fun awaitInputIdle(): Boolean {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (overlay.editing) {
            if (SystemClock.uptimeMillis() > deadline) return false
            clock.pause(150)
        }
        return true
    }

    // ---- snapshots --------------------------------------------------------------------------------

    private fun capture(): Capture = TreeCapture.capture(service, screenBox(), ownPackage, CAPTURE_MS)

    private fun locate(cap: Capture, el: ScreenElement): IndexedElement? =
        ScreenFormat.index(cap.windows).firstOrNull { it.fingerprint == el.fingerprint }

    /** Formats [cap] as the next snapshot (`s<n>`) and makes it the latest. */
    private fun publish(cap: Capture, detail: Boolean, generation: Long = abortGen): Snapshot {
        val id = MainRepair.nextScreenId()
        val pkg = cap.packageName
        val appLabel = label(pkg)
        val titles = cap.windows.mapNotNull { it.title } + ProtectedPages.titleTexts(cap.windows.firstOrNull()?.root, cap.screen.height)
        val protectedPage = ProtectedPages.isProtected(pkg, titles)
        // A note already shown on the previous screen of this app is not repeated: the model
        // still has it, and a warning on every screen reads as nagging.
        val seen = if (latest?.packageName == pkg) latest?.noteKeys ?: emptySet() else emptySet()
        val notes = mutableListOf<Pair<String, String>>()
        fun note(key: String, text: String) { if (key !in seen) notes += key to text }
        val lifted = lifted
        // l10n:keep agent screen result
        if (cap.ownAppInFront && !lifted.ownApp) note("ownApp", "提示：这是 DSH 自己的界面，DSH 不操作它。")
        // l10n:keep agent screen result
        if (protectedPage && !lifted.systemPages) note("protected", "提示：这是系统权限 / 安全相关页面，DSH 只看不操作；需要的话用 handoff 交给用户。")
        // l10n:keep agent screen result
        if (cap.truncated) note("truncated", "提示：页面元素太多，只读了一部分。")
        if (cap.hiddenWindowTitle != null) {
            val name = cap.hiddenWindowTitle.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: ""
            // l10n:keep agent screen result
            note("hidden", "提示：前台页面${name}不向 DSH 开放（应用标成了敏感页面）。读不到也点不了，需要就 handoff。")
        // l10n:keep agent screen result
        } else if (cap.unreadableWindowTitle != null) {
            val name = cap.unreadableWindowTitle.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: ""
            // l10n:keep agent screen result
            note("unreadable", "提示：当前无法读取这一屏${name}的有效内容，可能受敏感视图策略限制（Android 14 起密码、支付、验证页会被系统过滤）。读不到也点不了；能看图的模型可以 screen 加 image，否则请 handoff。")
        // l10n:keep agent screen result
        } else if (cap.windows.isEmpty()) note("empty", "提示：读不到前台界面（可能是锁屏、安全页面或系统界面）。")
        val previous = latest?.takeIf { it.packageName == pkg }?.screen?.keys
        fun format(notes: List<Pair<String, String>>) = ScreenFormat.format(
            ScreenInput(
                screenId = id, appLabel = appLabel, packageName = pkg,
                screenWidth = cap.screen.width, screenHeight = cap.screen.height, keyboardShown = cap.keyboardShown,
                windows = cap.windows, detail = detail, previousKeys = previous, notes = notes.map { it.second },
                unreadable = cap.unreadableWindowTitle != null,
            ),
        )
        var formatted = format(notes)
        var noteKeys = notes.map { it.first }.toSet()
        // Such inputs still read 「不可代填」 (their fingerprints must not change with the setting):
        // say once, on the screens that have one, that the person allowed filling them.
        if (lifted.secretFields && "secretFields" !in seen && formatted.elements.any { it.password || it.secret }) {
            // l10n:keep agent screen result
            notes += "secretFields" to "提示：用户已允许 DSH 填写密码和验证码，标着「不可代填」的输入框也可以输入。"
            noteKeys = noteKeys + "secretFields"
            formatted = format(notes)
        }
        val snap = Snapshot(
            id, generation, formatted, pkg, appLabel, protectedPage, cap.ownAppInFront, cap.windows.lastOrNull()?.id,
            cap.hiddenWindowTitle != null, cap.unreadableWindowTitle != null, cap.keyboardShown,
            cap.windows.lastOrNull()?.bounds, noteKeys,
        )
        synchronized(snapshotLock) { if (generation == abortGen) latest = snap }
        return snap
    }

    private fun toSnapshot(s: Snapshot, text: String = s.screen.text) =
        ScreenSnapshot(s.id, text, s.appLabel, s.packageName, secure = s.hidden, poorTree = s.screen.poorTree)

    /** A fresh text snapshot for an action result; null when the screen can't be read now. */
    private fun observeQuiet(generation: Long = abortGen): ScreenSnapshot? {
        if (generation != abortGen || !availability().ok) return null
        return try {
            val cap = capture()
            try { if (generation == abortGen) toSnapshot(publish(cap, false, generation)) else null } finally { cap.recycle() }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "observe failed", e)
            null
        }
    }

    // l10n:keep agent screen result
    private fun blank(reason: String): ScreenSnapshot {
        latest = null
        return ScreenSnapshot(MainRepair.nextScreenId(), "屏幕读不到：$reason", null, null)
    }

    private fun screenBox(): Box {
        val p = Point()
        @Suppress("DEPRECATION")
        displays.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
        return Box(0, 0, p.x.coerceAtLeast(1), p.y.coerceAtLeast(1))
    }

    private fun label(pkg: String?): String? {
        if (pkg == null) return null
        labels[pkg]?.let { return it }
        val name = try {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { return null }
        labels[pkg] = name
        return name
    }

    private fun cancelled(gen: Long) = abortGen != gen || Thread.currentThread().isInterrupted

    private fun short(s: String, max: Int = 24) = ScreenFormat.clip(s, max)

    companion object {
        private const val TAG = "DshA11y"

        /**
         * A `type` whose read-back differs from what was typed. An ordinary input says what it
         * holds now; a password or code field ([SafetyRules.isSecretTarget], as for the log's ••••)
         * never does: the tool result goes to the model.
         */
        internal fun readBackMismatch(got: String?, target: TargetInfo): String {
            // l10n:keep agent screen result
            val now = if (SafetyRules.isSecretTarget(target)) "这是密码 / 验证码框，框里的内容不回传"
            // l10n:keep agent screen result
            else "框里现在是" + ScreenMask.mask(got.orEmpty()).let { if (it.isEmpty()) "空" else "「${ScreenFormat.clip(it, 40)}」" }
            // l10n:keep agent screen result
            return "已输入，但回读不一致：$now。没有重试，也没有提交；请看新屏幕决定下一步。"
        }

        private const val OBSERVE_MS = 4_800L
        private const val CAPTURE_MS = 2_000L
        private const val COLLECT_MS = 100_000L
        private const val COLLECT_CHARS = 60_000
    }
}
