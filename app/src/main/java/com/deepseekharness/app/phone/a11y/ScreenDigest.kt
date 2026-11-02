package com.deepseekharness.app.phone.a11y

/**
 * The screen an action hands back: what the previous one already showed is not repeated.
 *
 * Element ids are handed out in reading order, so when the new screen has the same elements in
 * the same order their ids are the previous screen's ids. Then unchanged lines can be left out —
 * the model still holds the previous screen (or an earlier result in the same turn). When the
 * count or the order differs, or most lines changed, the whole screen goes out: the tokens saved
 * are not worth letting the model act on ids it has to guess.
 */
internal object ScreenDigest {
    // l10n:keep agent screen format
    private const val NOTE = "提示："
    // l10n:keep agent screen format
    private val ZONES = setOf("顶部", "中部", "底部")
    private val ELEMENT_LINE = Regex("""^\s*\[(\d+)\]""")

    fun text(previousId: String?, previous: FormattedScreen?, currentId: String, current: FormattedScreen): String {
        val full = current.text
        if (previous == null || previousId == null) return full
        val before = previous.elements.map { it.fingerprint }
        val now = current.elements.map { it.fingerprint }
        if (before.size != now.size) return full
        val changed = now.indices.filter { before[it] != now[it] }
        // l10n:keep agent screen format
        if (changed.isEmpty()) return "${headAndNotes(current)}\n（与 $previousId 相同，编号不变）"
        if (changed.size * 3 > now.size) return full
        val keep = changed.map { it + 1 }.toSet()
        val out = StringBuilder()
        for ((index, line) in current.text.split('\n').withIndex()) {
            val id = ELEMENT_LINE.find(line)?.groupValues?.get(1)?.toIntOrNull()
            when {
                index == 0 || line.startsWith(NOTE) -> out.append(line).append('\n')
                id != null -> if (id in keep) out.append(line).append('\n')
                // l10n:keep agent screen format
                line.trim() in ZONES -> out.append(line).append('\n')
            }
        }
        // l10n:keep agent screen format
        out.append("（只列变化：其余 ${now.size - changed.size} 项与 $previousId 相同，编号不变）")
        return out.toString()
    }

    /** Header line(s) and notes only: no element lines, no zone headings. */
    private fun headAndNotes(screen: FormattedScreen): String =
        screen.text.split('\n').filterIndexed { index, line -> index == 0 || line.startsWith(NOTE) }.joinToString("\n")
}
