package com.deepseekharness.app.phone.a11y

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/** Pure decisions shared by the main-screen driver and its host-side regression checks. */
internal object MainRepair {
    // A process restart or a reconnected service must not recycle an observation the caller holds.
    private val namespace = (SecureRandom().nextLong() and Long.MAX_VALUE).toString()
    private val sequence = AtomicLong()

    fun nextScreenId(): String = "s${namespace}${sequence.incrementAndGet().toString().padStart(20, '0')}"

    fun isCurrent(screenId: String, latestId: String?, snapshotGeneration: Long?, generation: Long): Boolean =
        latestId != null && screenId == latestId && snapshotGeneration == generation

    /** No new labels and an action refusal alone cannot prove that a list has ended. */
    fun collectStop(noNewPages: Int, actionAccepted: Boolean?, atKnownEnd: Boolean = false): String? = when {
        atKnownEnd -> "end"
        actionAccepted == false || noNewPages >= 2 -> "unconfirmed"
        else -> null
    }

    /** CollectionInfo is useful only when it is finite, complete and not a continuing hierarchy. */
    fun collectionAtEnd(total: Int, lastVisible: Int, hasMoreAction: Boolean, hierarchical: Boolean): Boolean =
        total > 0 && lastVisible >= total - 1 && !hasMoreAction && !hierarchical

    fun textWithinLimit(text: String): Boolean = text.length <= 2_000

    fun sameText(got: String?, expected: String): Boolean {
        if (got == expected) return true
        if (got == null || expected.isEmpty()) return false
        fun norm(value: String) = value.filterNot { it.isWhitespace() || it == '-' || it == '\u200b' }
        return norm(got) == norm(expected)
    }

    fun submitMessage(name: String, requested: Boolean, accepted: Boolean): String = when {
        !requested -> "已在「$name」写入，回读一致。written=true，submitted=false（未请求提交）。"
        accepted -> "已在「$name」写入，回读一致。written=true，submitted=true（已请求键盘发送 / 搜索，不代表业务已完成）。"
        else -> "已在「$name」写入，回读一致。written=true，submitted=false：输入框未接受回车；请点页面上的「发送」或「搜索」按钮，不要重复输入。"
    }
}
