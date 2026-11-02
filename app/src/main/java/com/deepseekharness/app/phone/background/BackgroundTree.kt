package com.deepseekharness.app.phone.background

import com.deepseekharness.app.phone.SafetyRules
import com.deepseekharness.app.phone.SensitiveWords

// The compact screen text format, shared with the main-screen reader. Pure Kotlin:
// an [Element] model plus [render], role words, sensitive-word detection and number
// redaction, so it can be unit-tested on the JVM and reused by the a11y module.
// The virtual-display engine walks AccessibilityNodeInfo into [Element]s (that part
// is Android-only) and calls [render] here.

/** Vertical zone a line sits in (顶部 / 中部 / 底部). */
enum class Region { TOP, MIDDLE, BOTTOM }

/**
 * One rendered element: already collapsed to a TalkBack-style unit (child text merged
 * into the clickable parent). Produced by the engine, consumed by [render].
 */
data class Element(
    /** 1-based number, valid only for the snapshot it belongs to. */
    val id: Int,
    /** Chinese role word (按钮, 输入框, 开关, 勾选框, 标签, 图片, 链接, 列表, 文本). */
    val role: String,
    /** Visible text / description / hint, length-capped and redacted. Never password contents. */
    val label: String,
    val region: Region,
    /** One indent level when inside a scrollable container. */
    val indent: Int = 0,
    /** State words: 已聚焦, 已选, 已勾选, 不可用, 可下滑, 密码·不可代填. */
    val states: List<String> = emptyList(),
    /** Hit by the SafetyGate sensitive-word list: rendered with a ⚠ prefix. */
    val sensitive: Boolean = false,
    /** First seen in the snapshot returned after an action (the "+" marker, set by the driver). */
    val isNew: Boolean = false,
    val packageName: String? = null,
    val isPassword: Boolean = false,
    val isSecretField: Boolean = false,
    val isEditable: Boolean = false,
    val viewId: String? = null,
)

object BackgroundTree {
    const val LINE_CAP = 60
    const val SCREEN_CAP = 3000

    fun isSensitiveLabel(label: String): Boolean = SensitiveWords.confirmHit(label, null) != null

    fun isSecretHint(text: String): Boolean = SensitiveWords.secretHit(text, null, null) != null

    /**
     * An element's label from its best [text] (text → description → hint). A password shows
     * nothing. Another secret element (a code field: [isSecretHint] on its hint / description)
     * shows its [name] (description or hint) and, when [filled] with text of its own,
     * [SafetyRules.MASK]: the field can be found, the code never read.
     */
    fun label(text: String, name: String, filled: Boolean, password: Boolean, secret: Boolean): String = when {
        password -> ""
        secret -> cap(if (filled) "$name ${SafetyRules.MASK}".trim() else name)
        else -> redact(cap(text))
    }

    /** Mask bank-card / ID-card runs to «银行卡 ****1234» style. Pure. */
    fun redact(text: String): String {
        var out = text
        // Mainland ID card first (17 digits + digit/X), so the broader card pattern can't swallow it.
        out = Regex("(?<!\\d)\\d{17}[\\dXx](?!\\d)").replace(out) { m ->
            // l10n:keep model screen format and matchers
            "«身份证 ****" + m.value.takeLast(4) + "»"
        }
        // 13–19 digit card numbers (optionally spaced/dashed); greedy so a long run is taken whole.
        out = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)").replace(out) { m ->
            val digits = m.value.filter(Char::isDigit)
            // l10n:keep model screen format and matchers
            "«银行卡 ****" + digits.takeLast(4) + "»"
        }
        return out
    }

    fun cap(text: String, max: Int = LINE_CAP): String {
        val clean = text.replace('\n', ' ').trim()
        return if (clean.length <= max) clean else clean.take(max - 1) + "…"
    }

    /**
     * Render the screen text. [screenId] e.g. "b1"; background screens carry the 后台屏 marker.
     * [overflow] is the count dropped past [SCREEN_CAP]; when > 0 a trailing hint is appended.
     */
    fun render(
        screenId: String,
        appLabel: String?,
        portrait: Boolean,
        keyboardUp: Boolean,
        elements: List<Element>,
        background: Boolean,
        poorTree: Boolean,
        overflow: Int = 0,
    ): String {
        val header = buildString {
            // l10n:keep model screen format and matchers
            append("屏幕 ").append(screenId)
            // l10n:keep model screen format and matchers
            if (background) append(" · 后台屏")
            appLabel?.let { append(" · ").append(it) }
            // l10n:keep model screen format and matchers
            append(" · ").append(if (portrait) "竖屏" else "横屏")
            // l10n:keep model screen format and matchers
            append(" · ").append(if (keyboardUp) "键盘弹出" else "键盘收起")
            // l10n:keep model screen format and matchers
            append(" · 共 ").append(elements.size).append(" 项")
        }
        val lines = ArrayList<String>()
        lines.add(header)
        // l10n:keep model screen format and matchers
        if (poorTree) lines.add("（本页文字信息不足，可能是游戏、画布或小程序）")
        var lastRegion: Region? = null
        for (e in elements) {
            if (e.region != lastRegion) {
                lines.add(regionLabel(e.region))
                lastRegion = e.region
            }
            lines.add(renderLine(e))
        }
        // l10n:keep model screen format and matchers
        if (overflow > 0) lines.add("  …另有 $overflow 项，用 collect 或 scroll")
        return lines.joinToString("\n")
    }

    private fun regionLabel(region: Region): String = when (region) {
        // l10n:keep model screen format and matchers
        Region.TOP -> "顶部"
        // l10n:keep model screen format and matchers
        Region.MIDDLE -> "中部"
        // l10n:keep model screen format and matchers
        Region.BOTTOM -> "底部"
    }

    private fun renderLine(e: Element): String {
        val sb = StringBuilder()
        sb.append("  ".repeat(1 + e.indent))
        if (e.isNew) sb.append("+ ")
        if (e.sensitive) sb.append("⚠ ")
        sb.append('[').append(e.id).append("] ")
        sb.append(e.role)
        val label = cap(e.label)
        if (label.isNotEmpty()) sb.append(' ').append(label)
        if (e.states.isNotEmpty()) sb.append("（").append(e.states.joinToString("，")).append("）")
        return sb.toString()
    }
}
