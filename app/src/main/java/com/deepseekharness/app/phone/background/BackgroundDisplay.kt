package com.deepseekharness.app.phone.background

import android.os.Build
import android.os.Bundle
import com.deepseekharness.app.phone.ActionResult
import com.deepseekharness.app.phone.Availability
import com.deepseekharness.app.phone.CollectResult
import com.deepseekharness.app.phone.PhoneAction
import com.deepseekharness.app.phone.PhoneDriver
import com.deepseekharness.app.phone.PhoneRegistry
import com.deepseekharness.app.phone.PhoneScreen
import com.deepseekharness.app.phone.ScreenSnapshot
import com.deepseekharness.app.phone.TargetInfo

/**
 * BACKGROUND [PhoneDriver] (screen ids `b<n>`): reads and drives the trusted virtual display
 * through whichever privileged channel is live (Shizuku or self-ADB). Registered in
 * [com.deepseekharness.app.phone.PhoneRegistry].backgroundDriver only while a task uses it.
 * All calls are serialised by the bridge; this class never retries a dispatched action.
 */
class BackgroundDisplay(
    private val channelProvider: () -> PrivilegedChannel?,
    private val releaseChannelProvider: () -> PrivilegedChannel?,
) : PhoneDriver {

    override val screen: PhoneScreen = PhoneScreen.BACKGROUND

    @Volatile private var currentPackage: String? = null
    private var counter = 0
    private var lastScreenId: String? = null
    private var lastElements: List<Element> = emptyList()
    private var previousLabels: Set<String> = emptySet()

    override fun availability(): Availability {
        if (Build.VERSION.SDK_INT < BackgroundPolicy.MIN_SDK)
            // l10n:keep agent screen protocol
            return Availability(false, "不打扰模式需要 Android 14 或更高版本")
        val channel = channelProvider()
        if (channel == null || !channel.alive())
            // l10n:keep agent screen protocol
            return Availability(false, "不打扰模式的特权通道未就绪")
        return Availability(true)
    }

    private fun channel(): PrivilegedChannel =
        // l10n:keep agent screen protocol
        channelProvider() ?: throw IllegalStateException("背景屏通道未就绪")

    override fun open(packageName: String): ActionResult {
        val request = Bundle().apply {
            putString(Proto.OP, Proto.OP_LAUNCH)
            putString(Proto.OWNER, "task")
            putString(Proto.PKG, packageName)
            putInt(Proto.SHORT_SIDE, 720)
            putInt(Proto.WANT_IMAGE, 0)
        }
        currentPackage = packageName
        val response = channel().execute(request)
        // l10n:keep agent screen protocol
        return toResult(response, "已在后台屏打开")
    }

    override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
        val pkg = currentPackage ?: return idleSnapshot()
        val request = Bundle().apply {
            putString(Proto.OP, Proto.OP_OBSERVE)
            putString(Proto.OWNER, "task")
            putString(Proto.PKG, pkg)
            putBoolean(Proto.DETAIL, detail)
            putBoolean(Proto.WANT_IMAGE, image)
        }
        return snapshot(channel().execute(request), markNew = false)
    }

    override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult {
        val seen = LinkedHashSet<String>()
        var pages = 0
        var stop = "listEnd"
        var last: ScreenSnapshot? = null
        while (pages < maxPages.coerceIn(1, 30)) {
            val snap = observe(detail = false, image = false)
            last = snap
            val before = seen.size
            for (e in lastElements) if (e.label.isNotEmpty()) seen.add(e.label)
            pages++
            if (until != null && seen.any { it.contains(until) }) { stop = "untilMatched"; break }
            if (seen.size == before) { stop = "listEnd"; break }
            if (pages >= maxPages) { stop = "pageLimit"; break }
            perform(PhoneAction.Scroll(screenId, listElementId, PhoneAction.Direction.DOWN))
        }
        return CollectResult(seen.toList(), pages, stop, last)
    }

    override fun target(screenId: String, elementId: Int): TargetInfo? {
        if (screenId != lastScreenId) return null
        val e = lastElements.getOrNull(elementId - 1) ?: return null
        return TargetInfo(
            screenId = screenId,
            elementId = elementId,
            role = e.role,
            label = e.label,
            packageName = e.packageName,
            appLabel = null,
            isPassword = e.isPassword,
            isSecretField = e.isSecretField,
            isEditable = e.isEditable,
            viewId = e.viewId,
        )
    }

    override fun perform(action: PhoneAction): ActionResult {
        // l10n:keep agent screen protocol
        val pkg = currentPackage ?: return ActionResult(false, "后台屏还没有打开应用")
        val request = Bundle().apply {
            putString(Proto.OWNER, "task")
            putString(Proto.PKG, pkg)
        }
        when (action) {
            is PhoneAction.Tap -> {
                request.putString(Proto.OP, Proto.OP_TAP)
                request.putInt(Proto.ELEMENT, action.elementId)
                request.putBoolean(Proto.LONG, action.long)
                fingerprint(action.elementId, request)
            }
            is PhoneAction.Type -> {
                request.putString(Proto.OP, Proto.OP_TEXT)
                request.putInt(Proto.ELEMENT, action.elementId)
                fingerprint(action.elementId, request)
                request.putString(Proto.TEXT, action.text)
                request.putBoolean(Proto.SUBMIT, action.submit)
                // 解除限制 › 密码和验证码: the privileged side refuses such inputs unless told so.
                request.putBoolean(Proto.ALLOW_SECRET, PhoneRegistry.unlocks().secretFields)
            }
            is PhoneAction.Scroll -> {
                request.putString(Proto.OP, Proto.OP_SCROLL)
                request.putString(Proto.DIR, action.direction.name.lowercase())
            }
            is PhoneAction.Key -> {
                val dir = when (action.key) {
                    PhoneAction.GlobalKey.BACK -> "back"
                    PhoneAction.GlobalKey.HOME -> "home"
                    // l10n:keep agent screen protocol
                    else -> return ActionResult(false, "后台屏不支持该全局键，请用返回或主页")
                }
                request.putString(Proto.OP, Proto.OP_KEY)
                request.putString(Proto.DIR, dir)
            }
            // l10n:keep agent screen protocol
            is PhoneAction.TapXY -> return ActionResult(false, "后台屏按元素编号操作，不用坐标")
        }
        // l10n:keep agent screen protocol
        return toResult(channel().execute(request), "已在后台屏执行")
    }

    override fun foregroundPackage(): String? = currentPackage

    fun release() {
        runCatching {
            // Cleanup may use an existing session, but must not connect or start a new process.
            releaseChannelProvider()?.execute(Bundle().apply {
                putString(Proto.OP, Proto.OP_RELEASE); putString(Proto.OWNER, "task")
            })
        }
        currentPackage = null
        lastScreenId = null
        lastElements = emptyList()
        previousLabels = emptySet()
    }

    private fun fingerprint(elementId: Int, request: Bundle) {
        val e = lastElements.getOrNull(elementId - 1) ?: return
        request.putString(Proto.EXPECT_ROLE, e.role)
        request.putString(Proto.EXPECT_LABEL, e.label)
        e.viewId?.let { request.putString(Proto.EXPECT_VIEW_ID, it) }
    }

    private fun toResult(response: Bundle, okMessage: String): ActionResult {
        val ok = response.getBoolean(Proto.OK)
        val message = if (ok) okMessage else Reasons.of(response.getString(Proto.ERROR))
        val snap = if (response.containsKey(Proto.COUNT) || response.getBoolean(Proto.SECURE))
            snapshot(response, markNew = true) else null
        return ActionResult(ok, message, snap)
    }

    private fun snapshot(response: Bundle, markNew: Boolean): ScreenSnapshot {
        val id = "b${++counter}"
        val pkg = response.getString(Proto.FG_PKG) ?: currentPackage
        val appLabel = response.getString(Proto.APP_LABEL)
        if (response.getBoolean(Proto.SECURE)) {
            lastScreenId = id; lastElements = emptyList(); previousLabels = emptySet()
            // l10n:keep agent screen protocol
            val text = "屏幕 $id · 后台屏 · 受保护页面（支付或密码），已交还用户"
            return ScreenSnapshot(id, text, appLabel, pkg, secure = true)
        }
        val elements = buildElements(response, markNew)
        lastElements = elements
        lastScreenId = id
        val poor = response.getBoolean(Proto.POOR)
        val portrait = response.getBoolean(Proto.PORTRAIT, true)
        val keyboardUp = response.getBoolean(Proto.KEYBOARD_UP, false)
        var text = BackgroundTree.render(id, appLabel, portrait, keyboardUp, elements, background = true, poorTree = poor)
        if (response.getBoolean(Proto.IMAGE_OMITTED)) {
            // l10n:keep agent screen protocol
            text += "\n（本页含敏感输入或控件树未完整读取，已省略截图；可继续使用已脱敏的文字信息）"
        }
        previousLabels = elements.map { it.label }.filter { it.isNotEmpty() }.toSet()
        val image = if (response.getBoolean(Proto.IMAGE_OMITTED)) null else response.getByteArray("imageBytes")
        return ScreenSnapshot(id, text, appLabel, pkg, secure = false, poorTree = poor,
            image = image, imageMime = if (image == null) null else response.getString("imageMime"))
    }

    private fun buildElements(response: Bundle, markNew: Boolean): List<Element> {
        val n = response.getInt(Proto.COUNT, 0)
        val role = response.getStringArray(Proto.A_ROLE) ?: return emptyList()
        val label = response.getStringArray(Proto.A_LABEL) ?: return emptyList()
        val viewIds = response.getStringArray(Proto.A_VIEW_ID)
        val states = response.getStringArray(Proto.A_STATES) ?: arrayOfNulls(n)
        val flags = response.getIntArray(Proto.A_FLAGS) ?: IntArray(n)
        val region = response.getIntArray(Proto.A_REGION) ?: IntArray(n)
        val indent = response.getIntArray(Proto.A_INDENT) ?: IntArray(n)
        val out = ArrayList<Element>(n)
        for (i in 0 until n) {
            val lbl = label[i].orEmpty()
            val f = flags.getOrElse(i) { 0 }
            out.add(
                Element(
                    id = i + 1,
                    role = role[i].orEmpty(),
                    label = lbl,
                    region = regionOf(region.getOrElse(i) { 1 }),
                    indent = indent.getOrElse(i) { 0 },
                    states = states[i]?.split('\u0001')?.filter { it.isNotEmpty() } ?: emptyList(),
                    sensitive = f and Proto.FLAG_SENSITIVE != 0,
                    isNew = markNew && lbl.isNotEmpty() && lbl !in previousLabels,
                    isPassword = f and Proto.FLAG_PASSWORD != 0,
                    isSecretField = f and Proto.FLAG_SECRET != 0,
                    isEditable = f and Proto.FLAG_EDITABLE != 0,
                    viewId = viewIds?.getOrNull(i),
                ),
            )
        }
        return out
    }

    private fun regionOf(code: Int): Region = when (code) {
        0 -> Region.TOP
        2 -> Region.BOTTOM
        else -> Region.MIDDLE
    }

    private fun idleSnapshot(): ScreenSnapshot {
        val id = "b${++counter}"
        lastScreenId = id; lastElements = emptyList()
        // l10n:keep agent screen protocol
        return ScreenSnapshot(id, "屏幕 $id · 后台屏 · 空闲，用 open 打开应用", null, null)
    }
}
