package com.deepseekharness.app.phone

import com.deepseekharness.app.i18n.AppStrings
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.net.Uri
import com.deepseekharness.app.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [UserGate] without the accessibility overlay: a heads-up notification with
 * 允许一次 / 拒绝 / 停止 (30 s → deny), and for `handoff` 继续 / 停止.
 *
 * The buttons fire broadcasts to a receiver registered for this one question, under an action
 * name that carries a fresh random nonce. Shared presentations also bind that nonce to the
 * current request, task lifetime and deadline. Native confirm/handoff wait on their bridge
 * worker; engine requests stay asynchronous. Taps inside SystemUI remain refused by
 * [SafetyRules]. The shared coordinator owns cancellation and presentation cleanup.
 */
class NotificationGate(
    private val app: Context,
    /** The one-line status ("DSH 正在操作 · …"); the app shows it on the engine's notification. */
    private val onStatus: (String?) -> Unit,
) : UserGate, PhonePromptPresenter {
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val ids = AtomicInteger(4200)

    fun available(): Boolean = runCatching {
        ensureChannel()
        channelAvailable(CHANNEL_CONFIRM, NotificationManager.IMPORTANCE_HIGH)
    }.getOrDefault(false)

    private fun channelAvailable(channel: String, minimum: Int): Boolean {
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val c = nm.getNotificationChannel(channel) ?: return false
        if (c.importance < minimum) return false
        if (Build.VERSION.SDK_INT >= 28 && c.group != null && nm.getNotificationChannelGroup(c.group)?.isBlocked == true) return false
        return true
    }

    override fun confirm(request: ConfirmRequest): Decision {
        PhoneRegistry.promptGate?.let { return it.confirm(request) }
        if (!available()) return Decision.UNAVAILABLE
        // Three buttons fit a heads-up: 本任务免确认 makes "allow" waive the rest of the task.
        val allow = when {
            request.offerTaskWaiver -> AppStrings.get(app, R.string.overlay_allow_task_no_ask)
            request.firstInApp -> AppStrings.get(app, R.string.overlay_allow_task)
            else -> AppStrings.get(app, R.string.overlay_allow_once)
        }
        val title = if (request.firstInApp) AppStrings.get(app, R.string.overlay_open_app, request.appLabel ?: AppStrings.get(app, R.string.overlay_this_app)) else AppStrings.get(app, R.string.overlay_confirm_title)
        val choice = ask(
            title = title,
            text = request.sentence,
            buttons = listOf(allow to "allow", AppStrings.get(app, R.string.overlay_decline) to "deny", AppStrings.get(app, R.string.overlay_stop) to "stop"),
            timeoutMs = request.timeoutMs.coerceIn(5_000, 120_000),
            dismissed = "deny",
            channel = CHANNEL_CONFIRM,
        )
        return when (choice) {
            "allow" -> when {
                request.offerTaskWaiver -> Decision.ALLOW_ALL_FOR_TASK
                request.firstInApp -> Decision.ALLOW_APP_FOR_TASK
                else -> Decision.ALLOW_ONCE
            }
            "stop" -> Decision.STOP
            else -> Decision.DENY
        }
    }

    override fun handoff(reason: String, timeoutMs: Long): HandoffResult = handoff(reason, timeoutMs, null)

    override fun handoff(reason: String, timeoutMs: Long, cause: HandoffCause?): HandoffResult {
        PhoneRegistry.promptGate?.let { return it.handoff(reason, timeoutMs, cause) }
        if (!available()) return HandoffResult.STOP
        val limit = timeoutMs.coerceIn(10_000, 600_000)
        val deadline = System.currentTimeMillis() + limit
        val title = cause?.let { PhonePromptUi.handoffCause(app, it) } ?: AppStrings.get(app, R.string.overlay_handoff_phone)
        // Re-post if the person swipes it away: the buttons are the only way to answer.
        repeat(3) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return HandoffResult.TIMEOUT
            when (ask(title, reason, listOf(AppStrings.get(app, R.string.overlay_continue) to "continue", AppStrings.get(app, R.string.overlay_stop) to "stop"), left, "dismissed", CHANNEL_CONFIRM, ongoing = true)) {
                "continue" -> return HandoffResult.CONTINUE
                "stop" -> return HandoffResult.STOP
                null -> return HandoffResult.TIMEOUT
            }
        }
        val left = deadline - System.currentTimeMillis()
        if (left > 0) Thread.sleep(left)
        return HandoffResult.TIMEOUT
    }

    override fun status(text: String?) = onStatus(text)

    /** Non-blocking presentation for the shared arbiter. Open uses an Activity PendingIntent. */
    override fun showPrompt(presentation: PromptPresentation): (() -> Unit)? {
        val p = presentation
        if (!p.live || !available()) return null
        val id = ids.incrementAndGet()
        val action = "${app.packageName}.PHONE_PROMPT.${p.nonce}"
        val pendingIntents = mutableListOf<PendingIntent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action || !p.live) return
                val raw = intent.getStringExtra(EXTRA_CHOICE)
                if (raw == "dismiss") { p.ticket.dismiss(); return }
                val choice = PromptChoice.entries.firstOrNull { it.name == raw } ?: return
                p.choose(choice)
            }
        }
        var registered = false
        fun close() {
            p.retire()
            pendingIntents.forEach { runCatching { it.cancel() } }
            if (registered) { runCatching { app.unregisterReceiver(receiver) }; registered = false }
            runCatching { nm.cancel(TAG, id) }
        }
        return try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, IntentFilter(action))
            registered = true
            fun broadcast(choice: String, code: Int): PendingIntent = PendingIntent.getBroadcast(app, id * 8 + code,
                Intent(action).setPackage(app.packageName).putExtra(EXTRA_CHOICE, choice),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT).also { pendingIntents += it }
            val open = PendingIntent.getActivity(app, id * 8 + 6, PhonePrompts.openIntent(app, p),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT).also { pendingIntents += it }
            val content = p.ticket.content
            val b = builder(CHANNEL_CONFIRM).setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(PhonePromptUi.title(app, content)).setContentText(content.text)
                .setStyle(Notification.BigTextStyle().bigText(content.text))
                .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(builder(CHANNEL_CONFIRM).setSmallIcon(R.drawable.ic_stat_dsh)
                    .setContentTitle(PhonePromptUi.title(app, content.kind)).build())
                .setContentIntent(open).setDeleteIntent(broadcast("dismiss", 7)).setAutoCancel(false)
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(p.ticket.remainingMs)
            // Native task/app waiver replaces its ordinary allow in the three notification slots.
            val choices = content.choices.let { all ->
                if (all.size > 3) all.filterNot { it == PromptChoice.ALLOW_ONCE } else all
            }
            choices.forEachIndexed { index, choice ->
                val pi = if (choice == PromptChoice.OPEN) open else broadcast(choice.name, index)
                b.addAction(Notification.Action.Builder(null, PhonePromptUi.label(app, choice), pi).build())
            }
            if (!p.live) { close(); return null }
            nm.notify(TAG, id, b.build())
            if (!p.markShown()) { close(); null } else ({ close() })
        } catch (_: Exception) { close(); null }
    }

    /** A distinct result notification, independent of ordinary chat notification preferences. */
    fun notifyFinished(taskId: Long, sessionId: String?, host: String?): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULT,
            AppStrings.get(app, R.string.phone_prompt_result_channel), NotificationManager.IMPORTANCE_HIGH))
        if (!channelAvailable(CHANNEL_RESULT, NotificationManager.IMPORTANCE_MIN)) return false
        val intent = com.deepseekharness.app.ui.QuickChatSheetActivity.createLaunchIntent(app)
        intent.data = Uri.parse("dsh-phone-result://task/$taskId")
        val pi = PendingIntent.getActivity(app, RESULT_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(RESULT_TAG, RESULT_ID, builder(CHANNEL_RESULT).setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(AppStrings.get(app, R.string.phone_prompt_result_title))
            .setContentText(AppStrings.get(app, R.string.phone_prompt_result_text))
            .setContentIntent(pi).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
        true
    }.getOrDefault(false)

    private fun builder(channel: String): Notification.Builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, channel)
        else @Suppress("DEPRECATION") Notification.Builder(app).setPriority(Notification.PRIORITY_HIGH)

    /** Posts the question and blocks for a button; null on timeout. */
    private fun ask(
        title: String,
        text: String,
        buttons: List<Pair<String, String>>,
        timeoutMs: Long,
        dismissed: String,
        channel: String,
        ongoing: Boolean = false,
    ): String? {
        ensureChannel()
        val id = ids.incrementAndGet()
        val action = "${app.packageName}.PHONE_GATE." + PhoneBridge.hex(16)
        val answer = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (answer.compareAndSet(null, intent.getStringExtra(EXTRA_CHOICE))) latch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, IntentFilter(action))
        }
        fun pending(choice: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            app,
            id * 8 + code,
            Intent(action).setPackage(app.packageName).putExtra(EXTRA_CHOICE, choice),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(app, channel)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(app).setPriority(Notification.PRIORITY_HIGH)
            }
            b.setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setOngoing(ongoing)
                .setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(app, channel) else @Suppress("DEPRECATION") Notification.Builder(app))
                        .setSmallIcon(R.drawable.ic_stat_dsh)
                        .setContentTitle(AppStrings.get(app, R.string.overlay_public_approval))
                        .build(),
                )
                .setDeleteIntent(pending(dismissed, 7))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setTimeoutAfter(timeoutMs)
            buttons.forEachIndexed { i, (label, choice) ->
                b.addAction(Notification.Action.Builder(null, label, pending(choice, i)).build())
            }
            nm.notify(TAG, id, b.build())
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) // InterruptedException = stop: propagate
            return answer.get()
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
            nm.cancel(TAG, id)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONFIRM, AppStrings.get(app, R.string.overlay_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = AppStrings.get(app, R.string.overlay_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "dsh-phone-gate"
        const val CHANNEL_CONFIRM = "dsh-phone-confirm"
        const val CHANNEL_RESULT = "dsh-phone-result"
        private const val RESULT_TAG = "dsh-phone-result"
        private const val RESULT_ID = 4300
        private const val EXTRA_CHOICE = "choice"
    }
}
