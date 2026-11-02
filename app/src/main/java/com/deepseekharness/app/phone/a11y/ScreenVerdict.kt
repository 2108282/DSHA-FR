package com.deepseekharness.app.phone.a11y

/**
 * What an action may honestly say about the screen after it.
 *
 * The one rule: **缺少观测依据不等于界面没变**。Empty fingerprints on both sides, or a screen
 * whose content is filtered (see [TreeCapture] `unreadableWindowTitle`), mean "cannot verify" —
 * saying 「没有明显变化」 there makes the model retry an action that already worked, and saying
 * 「可能已经到头了」 after a scroll makes it give up on a list it never reached the end of.
 */
internal object ScreenVerdict {
    const val UNCHANGED = "界面没有明显变化。"
    const val SCROLL_END = "内容没有变化，可能已经到头了。"
    // l10n:keep agent screen result
    const val UNVERIFIABLE = "（动作已提交，但无法验证结果：这一屏读不到内容，可能受敏感视图策略限制。）"
    const val KEYBOARD_HIDDEN = "键盘已收起。"
    const val KEYBOARD_SHOWN = "键盘已弹出。"

    /**
     * The sentence to append after 「已点击…」/「已返回…」. Empty when the tree changed (or the
     * window insets did): the new screen speaks for itself.
     *
     * @param comparable both before and after had readable elements to compare
     * @param changed the element fingerprints or the main window bounds differ
     * @param keyboard what happened to the IME, when that is what changed
     * @param scrolled the action was a scroll, so "unchanged" would read as "end of list"
     */
    fun note(comparable: Boolean, changed: Boolean, keyboard: String?, scrolled: Boolean): String = when {
        changed -> keyboard ?: ""
        keyboard != null -> keyboard
        !comparable -> UNVERIFIABLE
        scrolled -> SCROLL_END
        else -> UNCHANGED
    }
}
