package com.deepseekharness.app.phone.a11y

import com.deepseekharness.app.phone.SensitiveWords

// The compact screen text the model reads:
//
//   屏幕 s7 · 高德地图 · 竖屏 · 键盘收起 · 共 12 项
//   顶部
//     [1] 按钮 返回
//     [2] 输入框 "瑞幸咖啡" 已聚焦
//   列表 [4] 可下滑
//     [5] 瑞幸咖啡(科技园店) · 4.6分 · 320m · 营业中
//         [6] 按钮 路线   [7] 按钮 电话
//   底部
//     [11] 标签 综合排序 · 已选   [12] 标签 距离优先
//
// Rules: one element per "interactive or has text" node, layout nodes folded, a
// clickable row takes the text of its non-interactive descendants (TalkBack's
// reading unit); text → contentDescription → hint → view-id tail; 60 chars per
// label, ~3000 per page with "…另有 N 项"; zones 顶部 / 中部 / 底部 by position, one
// indent level per scrollable; `⚠` where SafetyGate will ask, `+` on what is new
// since the previous snapshot; password contents never rendered. Plain Kotlin.

class ScreenInput(
    val screenId: String,
    val appLabel: String?,
    val packageName: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val keyboardShown: Boolean,
    /** Top-most first; the last one is the main window, the others render as 弹窗. */
    val windows: List<UiWindow>,
    val detail: Boolean = false,
    /** Element keys of the previous snapshot of the same app (`+` marks); null = no marks. */
    val previousKeys: Set<String>? = null,
    /** Extra lines under the header (protected page, secure window …). */
    val notes: List<String> = emptyList(),
    /** The front window gave us no content at all (probably filtered): not the same as a poor tree. */
    val unreadable: Boolean = false,
    val charBudget: Int = 3000,
)

enum class ElementKind { ITEM, INPUT, LIST }

/** One numbered element of a snapshot. */
class ScreenElement(
    val id: Int,
    val windowId: Int,
    val packageName: String?,
    /** Index into the capture's native nodes. */
    val ref: Int,
    val kind: ElementKind,
    val role: String,
    /** As rendered: masked, ≤ 60 chars. For an input, its name (never its content). */
    val label: String,
    /** Masked, uncapped (≤ 300): the row with its texts, for collect and the action log. */
    val fullText: String,
    val bounds: Box,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val secret: Boolean,
    val checkable: Boolean,
    val scroll: Int,
    val horizontal: Boolean,
    val viewId: String?,
    /** The sensitive word that makes tapping this ask first (`⚠`), if any. */
    val sensitiveWord: String?,
    /** Enclosing list's id. */
    val listId: Int?,
    /** Enclosing row's id, for a control inside a row (路线 inside a POI card). */
    val parentId: Int?,
    /** Re-verified before every action: window | bounds | role | text (| checked). */
    val fingerprint: String,
    /** What `+` compares with the previous snapshot. */
    val key: String,
)

class FormattedScreen(
    val text: String,
    val elements: List<ScreenElement>,
    /** Nodes with any text or description (poor tree below [ScreenFormat.POOR_TREE]). */
    val textNodes: Int,
    val poorTree: Boolean,
    val keys: Set<String>,
    /** Elements left out by the page budget. */
    val hidden: Int,
) {
    fun element(id: Int): ScreenElement? = elements.getOrNull(id - 1)?.takeIf { it.id == id } ?: elements.firstOrNull { it.id == id }
}

/** Every element of a capture regardless of the page budget (re-verification, collect). */
class IndexedElement(
    val fingerprint: String,
    val ref: Int,
    val windowId: Int,
    val kind: ElementKind,
    val role: String,
    val bounds: Box,
    val fullText: String,
    /** For a list: its items' texts in on-screen order. */
    val itemTexts: List<String>,
)

object ScreenFormat {
    const val LABEL_CAP = 60
    const val FULL_CAP = 300
    const val POOR_TREE = 5
    private const val ROW_CAP = 64
    private const val MERGE_LABEL = 16
    private const val NEW_RATIO_LIMIT = 0.6

    // l10n:keep model screen format
    const val POOR_TREE_NOTE = "提示：本页文字很少（游戏、自绘界面常见）。能看图的模型可以 screen 加 image；否则请用户协助。"

    fun format(input: ScreenInput): FormattedScreen {
        val textNodes = input.windows.sumOf { w -> w.root?.let(::countText) ?: 0 }
        val lines = mutableListOf<Line>()
        val windows = input.windows.filter { it.root != null }
        for ((index, window) in windows.withIndex()) {
            val items = flatten(window.root!!, window, null).sortedWith(compareBy({ it.node.bounds.top }, { it.node.bounds.left }))
            if (index < windows.size - 1) {
                if (items.isEmpty()) continue
                // l10n:keep model screen format
                lines += Line.Header(if (window.title.isNullOrBlank()) "弹窗" else "弹窗 · ${clip(clean(window.title) ?: "", 20)}")
                emit(items, 2, lines, reserved = false, list = null, parent = null)
            } else {
                zones(items, input.screenHeight, lines)
            }
        }

        val header = StringBuilder()
        val notes = input.notes.toMutableList()
        val poor = textNodes < POOR_TREE && windows.isNotEmpty()
        // 读不到内容(大概率被敏感视图策略挡了)不能说成"游戏、自绘界面",那会把模型带到
        // 另一个方向;这时 publish() 已经给了一条更准确的说明。
        if (poor && !input.unreadable) notes += POOR_TREE_NOTE

        // Page budget: keep the 底部 zone (navigation) and cut the middle with a hint.
        val headerLen = 40 + (input.appLabel?.length ?: 0) + notes.sumOf { it.length + 1 }
        val lengths = lines.map { it.estimate(input.detail) }
        val total = headerLen + lengths.sum()
        var kept: List<Line> = lines
        var hidden = 0
        if (total > input.charBudget) {
            val reservedLen = lines.indices.filter { lines[it].reserved }.sumOf { lengths[it] }
            val keepReserved = headerLen + reservedLen + 60 < input.charBudget * 2 / 3
            var available = input.charBudget - headerLen - 40 - (if (keepReserved) reservedLen else 0)
            val out = mutableListOf<Line>()
            var cut = false
            for ((i, line) in lines.withIndex()) {
                if (keepReserved && line.reserved) { out += line; continue }
                if (!cut && lengths[i] <= available) { out += line; available -= lengths[i]; continue }
                if (!cut) { cut = true; out += Line.More(line.indent) }
                hidden += line.elementCount()
            }
            if (hidden > 0) out.filterIsInstance<Line.More>().forEach { it.count = hidden }
            kept = out
        }

        // Ids in reading order.
        val ids = HashMap<Item, Int>()
        var next = 1
        for (line in kept) for (item in line.items()) ids[item] = next++
        val count = next - 1

        val prev = input.previousKeys
        val keyOf = HashMap<Item, String>()
        for (item in ids.keys) keyOf[item] = item.key()
        val markNew = prev != null && count > 0 &&
            ids.keys.count { it.kind != ElementKind.LIST && keyOf[it] !in prev }.toDouble() / count <= NEW_RATIO_LIMIT

        // l10n:keep model screen format
        val orientation = if (input.screenWidth > input.screenHeight) "横屏" else "竖屏"
        // l10n:keep model screen format
        val keyboard = if (input.keyboardShown) "键盘弹出" else "键盘收起"
        // l10n:keep model screen format
        val app = clean(input.appLabel) ?: input.packageName ?: "未知应用"
        // l10n:keep model screen format
        header.append("屏幕 ${input.screenId} · $app · $orientation · $keyboard · 共 $count 项")
        for (note in notes) header.append('\n').append(note)
        // l10n:keep model screen format
        if (count == 0) header.append('\n').append("（没有可读的元素）")
        for (line in kept) {
            header.append('\n')
            header.append(line.render(ids, input.detail) { item -> markNew && item.kind != ElementKind.LIST && keyOf[item] !in prev })
        }

        val elements = ids.entries.sortedBy { it.value }.map { (item, id) ->
            val listItem = item.list
            val parentItem = item.parent
            item.toElement(id, listItem?.let { ids[it] }, parentItem?.let { ids[it] }, keyOf[item]!!)
        }
        return FormattedScreen(header.toString(), elements, textNodes, poor, keyOf.values.toSet(), hidden)
    }

    /** All elements of [windows] (no budget, no ids), for matching a fingerprint or reading a list. */
    fun index(windows: List<UiWindow>): List<IndexedElement> {
        val out = mutableListOf<IndexedElement>()
        fun walk(items: List<Item>) {
            for (item in items) {
                out += IndexedElement(
                    item.fingerprint(), item.node.ref, item.window.id, item.kind, item.role, item.node.bounds,
                    item.fullText(),
                    if (item.kind == ElementKind.LIST) item.subs.map { it.fullText() }.filter { it.isNotBlank() } else emptyList(),
                )
                walk(item.subs)
            }
        }
        for (window in windows) window.root?.let { walk(flatten(it, window, null)) }
        return out
    }

    /**
     * The list [fingerprint] names, in a newer capture. Scrolling a list can move it (a
     * collapsing app bar above it shrinks or grows), so besides an exact match this takes the
     * list with the same window and identity (class, id, description) that overlaps
     * [lastBounds] the most.
     */
    fun followList(elements: List<IndexedElement>, fingerprint: String, lastBounds: Box): IndexedElement? {
        elements.firstOrNull { it.fingerprint == fingerprint }?.let { return it }
        val parts = fingerprint.split('|', limit = 3)
        if (parts.size < 3) return null
        return elements.asSequence()
            .filter { it.kind == ElementKind.LIST }
            .filter { val p = it.fingerprint.split('|', limit = 3); p.size == 3 && p[0] == parts[0] && p[2] == parts[2] }
            .map { it to it.bounds.intersect(lastBounds).area }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    // ---- flattening -------------------------------------------------------------------------

    private class Item(
        val node: UiNode,
        val window: UiWindow,
        val kind: ElementKind,
        val role: String,
        /** Masked, single-line, uncapped. Input: its name. */
        val label: String,
        val states: List<String>,
        /** Input content (masked); null for empty / password / secret. */
        val content: String?,
        val subs: List<Item>,
        val checkedState: Boolean?,
        val secret: Boolean,
        val horizontal: Boolean = false,
    ) {
        var list: Item? = null
        var parent: Item? = null
        // l10n:keep model screen format
        val sensitive: String? = if (kind == ElementKind.ITEM && (node.clickable || node.longClickable || node.checkable) && role != "文本")
            SensitiveWords.confirmHit(label, node.viewId) else null

        fun fingerprint(): String {
            val identity = when (kind) {
                ElementKind.LIST -> "${node.className}|${node.viewId}|$label"
                ElementKind.INPUT -> "$label|${node.viewId}|${node.password}"
                ElementKind.ITEM -> label
            }
            return "w${window.id}|${node.bounds}|$role|$identity" + (checkedState?.let { "|c=$it" } ?: "")
        }

        fun key() = "$role|${clip(label, LABEL_CAP)}"

        fun fullText(): String {
            val parts = mutableListOf<String>()
            if (label.isNotEmpty()) parts += label
            if (kind == ElementKind.INPUT && content != null) parts += "\"$content\""
            if (kind != ElementKind.LIST) for (sub in subs) {
                if (sub.kind == ElementKind.LIST) sub.subs.forEach { if (it.label.isNotEmpty()) parts += it.label }
                // l10n:keep model screen format
                else if (sub.role == "文本" || sub.role == "图片" || sub.role == "标题" || sub.role == "") { if (sub.label.isNotEmpty()) parts += sub.label }
            }
            return clip(parts.distinct().joinToString(" · "), FULL_CAP)
        }

        fun toElement(id: Int, listId: Int?, parentId: Int?, key: String) = ScreenElement(
            id = id, windowId = window.id, packageName = window.packageName, ref = node.ref, kind = kind, role = role,
            label = clip(label, LABEL_CAP), fullText = fullText(), bounds = node.bounds,
            clickable = node.clickable, longClickable = node.longClickable, editable = kind == ElementKind.INPUT,
            password = node.password, secret = secret, checkable = node.checkable, scroll = node.scroll,
            horizontal = horizontal, viewId = node.viewId, sensitiveWord = sensitive, listId = listId, parentId = parentId,
            fingerprint = fingerprint(), key = key,
        )
    }

    private fun actionable(n: UiNode) = n.clickable || n.longClickable || n.checkable

    private fun isList(n: UiNode) = n.scrollable && n.children.isNotEmpty() && !n.editable

    private fun flatten(node: UiNode, window: UiWindow, parentClass: String?): List<Item> = when {
        node.editable -> listOf(input(node, window))
        isList(node) -> listOf(list(node, window))
        actionable(node) -> action(node, window, parentClass)
        else -> {
            val own = clean(node.text) ?: clean(node.desc)
            val children = node.children.flatMap { flatten(it, window, node.className) }
            if (own == null) children
            else listOf(text(node, window, own, parentClass)) +
                // l10n:keep model screen format
                children.filterNot { it.kind == ElementKind.ITEM && it.subs.isEmpty() && it.role == "文本" && it.label == own }
        }
    }

    private fun text(node: UiNode, window: UiWindow, label: String, parentClass: String?): Item {
        val role = when {
            // l10n:keep model screen format
            node.heading -> "标题"
            // l10n:keep model screen format
            role(node, true, parentClass) == "标签" -> "标签" // e.g. the selected bottom tab, often not clickable
            // l10n:keep model screen format
            node.text.isNullOrBlank() && isImage(node) -> "图片"
            // l10n:keep model screen format
            else -> "文本"
        }
        // l10n:keep model screen format
        val states = if (node.selected) listOf("已选") else emptyList()
        return Item(node, window, ElementKind.ITEM, role, label, states, null, emptyList(), null, false)
    }

    private fun input(node: UiNode, window: UiWindow): Item {
        val hint = clean(node.hint) ?: if (node.showingHint) clean(node.text) else null
        val secretWord = SensitiveWords.secretHit(hint, node.desc, node.viewId)
        val name = clean(node.desc) ?: hint ?: idTail(node.viewId) ?: ""
        val secret = secretWord != null && !node.password
        val role = when {
            // l10n:keep model screen format
            node.password -> "输入框(密码，不可代填)"
            // l10n:keep model screen format
            secret -> "输入框(${secretLabel(secretWord)}，不可代填)"
            // l10n:keep model screen format
            else -> "输入框"
        }
        val content = if (node.password || secret || node.showingHint) null else clean(node.text)
        val states = mutableListOf<String>()
        // l10n:keep model screen format
        if (content == null && !node.password && !secret) states += "空"
        // l10n:keep model screen format
        if (node.focused) states += "已聚焦"
        // l10n:keep model screen format
        if (!node.enabled) states += "不可用"
        return Item(node, window, ElementKind.INPUT, role, name, states, content, emptyList(), null, secret)
    }

    private fun list(node: UiNode, window: UiWindow): Item {
        val subs = node.children.flatMap { flatten(it, window, node.className) }
        val horizontal = node.scroll and (Scroll.LEFT or Scroll.RIGHT) != 0 ||
            node.className.contains("Horizontal") || node.className.contains("ViewPager") ||
            (node.children.size >= 2 && node.children[0].bounds.rowOverlap(node.children[1].bounds) > 0.5 &&
                node.children[1].bounds.left >= node.children[0].bounds.right - 2)
        val forward = node.scroll and Scroll.FORWARD != 0
        val backward = node.scroll and Scroll.BACKWARD != 0
        val states = if (horizontal) {
            val right = node.scroll and Scroll.RIGHT != 0 || forward
            val left = node.scroll and Scroll.LEFT != 0 || backward
            // l10n:keep model screen format
            listOfNotNull(when { left && right -> "可左右滑"; right -> "可右滑"; left -> "可左滑"; else -> null })
        } else {
            val down = node.scroll and Scroll.DOWN != 0 || forward
            val up = node.scroll and Scroll.UP != 0 || backward
            // l10n:keep model screen format
            listOfNotNull(when { up && down -> "可上下滑"; down -> "可下滑"; up -> "可上滑"; else -> null })
        }
        val label = clean(node.desc) ?: ""
        // l10n:keep model screen format
        return Item(node, window, ElementKind.LIST, "列表", label, states, null, subs, null, false, horizontal)
    }

    private fun action(node: UiNode, window: UiWindow, parentClass: String?): List<Item> {
        val texts = mutableListOf<String>()
        val subs = mutableListOf<Item>()
        fun gather(n: UiNode) {
            for (c in n.children) when {
                c.editable -> subs += input(c, window)
                isList(c) -> subs += list(c, window)
                actionable(c) -> subs += action(c, window, n.className)
                else -> { (clean(c.text) ?: clean(c.desc))?.let { texts += it }; gather(c) }
            }
        }
        gather(node)

        // A row with one non-clickable switch / checkbox inside reads as that switch (Settings rows).
        var checkNode: UiNode? = if (node.checkable) node else null
        var checkRole: String? = null
        val checks = subs.filter { it.kind == ElementKind.ITEM && it.node.checkable && !it.node.clickable && it.subs.isEmpty() }
        if (checks.size == 1) {
            val check = checks[0]
            subs.remove(check)
            if (checkNode == null) checkNode = check.node
            checkRole = check.role
            if (check.label.isNotEmpty() && check.label !in texts) texts += check.label
        }

        val own = clean(node.text) ?: clean(node.desc)
        val merged = texts.distinct().filter { it != own }
        val readable = own ?: merged.joinToString(" · ").ifEmpty { null } ?: clean(node.hint)
        if (readable == null && subs.isNotEmpty() && checkNode == null) return subs // a wrapper: its controls speak for it
        val label = readable ?: idTail(node.viewId)

        var role = role(node, own != null, parentClass)
        // l10n:keep model screen format
        if (checkRole != null && (role == "" || role == "按钮" || role == "勾选框")) role = checkRole
        // l10n:keep model screen format
        if (label == null && role.isEmpty()) role = "按钮"
        val states = mutableListOf<String>()
        val check = checkNode
        when {
            check == null -> {}
            // l10n:keep model screen format
            role == "开关" -> states += if (check.checked) "开" else "关"
            // l10n:keep model screen format
            role == "勾选框" || role == "单选" -> states += if (check.checked) "✓" else "空"
            // l10n:keep model screen format
            check.checked -> states += "已勾选"
        }
        // l10n:keep model screen format
        if (node.selected) states += "已选"
        // l10n:keep model screen format
        if (role == "滑块" && node.rangeCurrent != null) {
            val max = node.rangeMax
            states += if (max != null && max > 0f && max <= 1000f && max != 100f) "${fmt(node.rangeCurrent)}/${fmt(max)}" else "${fmt(node.rangeCurrent)}"
        }
        clean(node.stateDesc)?.let { sd ->
            // l10n:keep model screen format
            if (role != "开关" && role != "勾选框" && role != "单选" && sd.length <= 10 && sd != label && sd !in states) states += sd
        }
        // l10n:keep model screen format
        if (!node.enabled) states += "不可用"
        val item = Item(node, window, ElementKind.ITEM, role, label ?: "", states, null, subs, check?.checked, false)
        return listOf(item)
    }

    private fun role(node: UiNode, ownText: Boolean, parentClass: String?): String {
        val cls = node.className.substringAfterLast('.')
        val hint = node.roleHint
        return when {
            // l10n:keep model screen format
            cls.contains("Switch") || cls == "ToggleButton" || hint == "switch" || hint == "开关" -> "开关"
            // l10n:keep model screen format
            cls == "CheckBox" || cls == "CheckedTextView" || hint == "checkbox" || hint == "复选框" -> "勾选框"
            // l10n:keep model screen format
            cls == "RadioButton" || hint == "radio" || hint == "radiobutton" || hint == "单选按钮" -> "单选"
            // l10n:keep model screen format
            cls == "SeekBar" || cls == "RatingBar" || cls.contains("Slider") || hint == "slider" || hint == "滑块" -> "滑块"
            // l10n:keep model screen format
            cls == "Spinner" || hint == "combobox" || hint == "popupbutton" || hint == "下拉列表" -> "下拉框"
            // l10n:keep model screen format
            hint == "link" || hint == "链接" -> "链接"
            // l10n:keep model screen format
            cls.contains("TabView") || cls.endsWith("\$Tab") || cls == "Tab" || hint == "tab" || hint == "标签" ||
                cls.contains("NavigationItemView") || cls.contains("NavigationBarItemView") ||
                // l10n:keep model screen format
                (parentClass != null && (parentClass.contains("TabWidget") || parentClass.contains("TabLayout") || parentClass.contains("BottomNavigation"))) -> "标签"
            // l10n:keep model screen format
            node.checkable -> "勾选框"
            // l10n:keep model screen format
            cls == "Button" || cls == "ImageButton" || cls.endsWith("Button") || hint == "button" || hint == "按钮" -> "按钮"
            // l10n:keep model screen format
            isImage(node) -> "按钮"
            // l10n:keep model screen format
            ownText && node.children.isEmpty() -> "按钮"
            else -> ""
        }
    }

    // ---- lines --------------------------------------------------------------------------------

    private sealed class Line(val indent: Int, val reserved: Boolean) {
        abstract fun items(): List<Item>
        open fun elementCount() = items().size
        abstract fun estimate(detail: Boolean): Int
        abstract fun render(ids: Map<Item, Int>, detail: Boolean, isNew: (Item) -> Boolean): String

        class Header(val text: String, reserved: Boolean = false) : Line(0, reserved) {
            override fun items() = emptyList<Item>()
            override fun estimate(detail: Boolean) = text.length + 1
            override fun render(ids: Map<Item, Int>, detail: Boolean, isNew: (Item) -> Boolean) = text
        }

        class More(indent: Int) : Line(indent, false) {
            var count = 0
            override fun items() = emptyList<Item>()
            override fun estimate(detail: Boolean) = 30
            override fun render(ids: Map<Item, Int>, detail: Boolean, isNew: (Item) -> Boolean) =
                // l10n:keep model screen format
                " ".repeat(indent) + "…另有 $count 项，用 collect 或 scroll"
        }

        class Row(indent: Int, reserved: Boolean, val row: List<Item>) : Line(indent, reserved) {
            override fun items() = row
            override fun estimate(detail: Boolean) = indent + row.sumOf { body(it, 99, detail, false).length + 3 }
            override fun render(ids: Map<Item, Int>, detail: Boolean, isNew: (Item) -> Boolean) =
                " ".repeat(indent) + row.joinToString("   ") { body(it, ids[it]!!, detail, isNew(it)) }
        }

        class ListHead(indent: Int, reserved: Boolean, val list: Item) : Line(indent, reserved) {
            override fun items() = listOf(list)
            override fun estimate(detail: Boolean) = indent + body(list, 99, detail, false).length + 1
            override fun render(ids: Map<Item, Int>, detail: Boolean, isNew: (Item) -> Boolean) =
                " ".repeat(indent) + body(list, ids[list]!!, detail, false)
        }
    }

    // l10n:keep model screen format
    private enum class Zone(val title: String) { TOP("顶部"), MIDDLE("中部"), BOTTOM("底部") }

    private fun zones(items: List<Item>, screenHeight: Int, out: MutableList<Line>) {
        val h = screenHeight.coerceAtLeast(1)
        fun zone(item: Item): Zone {
            val b = item.node.bounds
            return when {
                b.centerY < h * 0.2 -> Zone.TOP
                b.centerY > h * 0.86 -> Zone.BOTTOM
                else -> Zone.MIDDLE
            }
        }
        val byZone = items.groupBy(::zone)
        byZone[Zone.TOP]?.let { out += Line.Header(Zone.TOP.title); emit(it, 2, out, false, null, null) }
        byZone[Zone.MIDDLE]?.let { middle ->
            // A list in the middle stands as its own header (the example's `列表 [4] 可下滑`).
            var needHeader = true
            var run = mutableListOf<Item>()
            fun flush() { if (run.isNotEmpty()) { if (needHeader) out += Line.Header(Zone.MIDDLE.title); needHeader = false; emit(run, 2, out, false, null, null); run = mutableListOf() } }
            for (item in middle) {
                if (item.kind == ElementKind.LIST) { flush(); emit(listOf(item), 0, out, false, null, null); needHeader = true }
                else run += item
            }
            flush()
        }
        byZone[Zone.BOTTOM]?.let { out += Line.Header(Zone.BOTTOM.title, reserved = true); emit(it, 2, out, true, null, null) }
    }

    private fun emit(items: List<Item>, indent: Int, out: MutableList<Line>, reserved: Boolean, list: Item?, parent: Item?) {
        var i = 0
        while (i < items.size) {
            val item = items[i]
            item.list = list
            item.parent = parent
            if (item.kind == ElementKind.LIST) {
                out += Line.ListHead(indent, reserved, item)
                emit(item.subs, indent + 2, out, reserved, item, null)
                i++
                continue
            }
            val row = mutableListOf(item)
            var length = body(item, 99, false, false).length
            while (i + 1 < items.size && mergeable(row.last(), items[i + 1])) {
                val nextItem = items[i + 1]
                val add = body(nextItem, 99, false, false).length + 3
                if (indent + length + add > ROW_CAP) break
                nextItem.list = list
                nextItem.parent = parent
                row += nextItem
                length += add
                i++
            }
            out += Line.Row(indent, reserved, row)
            for (member in row) if (member.subs.isNotEmpty()) emit(member.subs, indent + 4, out, reserved, list, member)
            i++
        }
    }

    private fun mergeable(a: Item, b: Item) =
        a.kind == ElementKind.ITEM && b.kind == ElementKind.ITEM && a.subs.isEmpty() && b.subs.isEmpty() &&
            a.role.isNotEmpty() && a.role == b.role &&
            a.label.length <= MERGE_LABEL && b.label.length <= MERGE_LABEL &&
            a.node.bounds.rowOverlap(b.node.bounds) >= 0.5 && b.node.bounds.left >= a.node.bounds.left

    private fun body(item: Item, id: Int, detail: Boolean, isNew: Boolean): String {
        val sb = StringBuilder()
        if (item.kind == ElementKind.LIST) {
            // l10n:keep model screen format
            sb.append("列表 [").append(id).append(']')
            if (item.label.isNotEmpty()) sb.append(' ').append(clip(item.label, LABEL_CAP))
            if (item.states.isNotEmpty()) sb.append(if (item.label.isNotEmpty()) " · " else " ").append(item.states.joinToString(" · "))
        } else {
            if (isNew) sb.append('+')
            if (item.sensitive != null) sb.append('⚠')
            sb.append('[').append(id).append(']')
            if (item.role.isNotEmpty()) sb.append(' ').append(item.role)
            val label = clip(item.label, LABEL_CAP)
            if (label.isNotEmpty()) sb.append(' ').append(label)
            if (item.kind == ElementKind.INPUT && item.content != null) {
                sb.append(" \"").append(clip(item.content, LABEL_CAP)).append('"')
                if (item.states.isNotEmpty()) sb.append(' ').append(item.states.joinToString(" · "))
            } else if (item.states.isNotEmpty()) {
                sb.append(if (label.isNotEmpty() || item.role.isNotEmpty()) " · " else " ").append(item.states.joinToString(" · "))
            }
            // l10n:keep model screen format
            if (label.isEmpty() && item.role.isNotEmpty() && item.kind == ElementKind.ITEM && item.states.isEmpty()) sb.append(" (无文字)")
        }
        if (detail) sb.append(" @").append(item.node.bounds.centerX).append(',').append(item.node.bounds.centerY)
        return sb.toString()
    }

    // ---- text helpers -------------------------------------------------------------------------

    private val space = Regex("\\s+")
    private val invisible = Regex("[\\u200B-\\u200F\\u2060\\uFEFF]")

    /** Single-line, masked, null when blank. */
    fun clean(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val flat = space.replace(invisible.replace(value, ""), " ").trim()
        return if (flat.isEmpty()) null else ScreenMask.mask(flat)
    }

    fun clip(value: String, cap: Int) = if (value.length <= cap) value else value.take(cap - 1) + "…"

    fun idTail(viewId: String?): String? = viewId?.substringAfterLast(":id/")?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    private fun isImage(node: UiNode) = node.className.endsWith("ImageView") || node.className.endsWith("Image") || node.roleHint == "image" || node.roleHint == "img"

    private fun secretLabel(word: String) = when (word) {
        // l10n:keep model screen format
        "支付密码" -> "支付密码"
        // l10n:keep model screen format
        "密码", "password", "passcode", "passwd", "pwd" -> "密码"
        // l10n:keep model screen format
        "安全码", "cvv", "cvc" -> "安全码"
        // l10n:keep model screen format
        else -> "验证码"
    }

    private fun fmt(value: Float) = if (value == value.toLong().toFloat()) value.toLong().toString() else "%.1f".format(value)

    /** Nodes with text or a description in [windows]: below [POOR_TREE] the page is "poor". */
    fun textCount(windows: List<UiWindow>) = windows.sumOf { w -> w.root?.let(::countText) ?: 0 }

    private fun countText(node: UiNode): Int =
        (if (!node.text.isNullOrBlank() || !node.desc.isNullOrBlank()) 1 else 0) + node.children.sumOf(::countText)
}
