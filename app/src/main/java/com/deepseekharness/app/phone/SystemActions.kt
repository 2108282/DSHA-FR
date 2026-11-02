package com.deepseekharness.app.phone

import com.deepseekharness.app.i18n.AppStrings
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.deepseekharness.app.ui.MainActivity
import com.deepseekharness.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Whether one of this app's activities is resumed (the clipboard and background-start rules care). */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    private val resumed = AtomicInteger()
    private val resumedActivities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Activity, Boolean>())
    @Volatile private var registered = false

    val visible: Boolean get() = resumed.get() > 0
    fun isResumed(activity: Activity): Boolean = synchronized(resumedActivities) { activity in resumedActivities }

    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        synchronized(resumedActivities) { if (resumedActivities.add(activity)) resumed.incrementAndGet() }
    }

    override fun onActivityPaused(activity: Activity) {
        synchronized(resumedActivities) { if (resumedActivities.remove(activity)) resumed.decrementAndGet() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = onActivityPaused(activity)
}

/** System tools on Android: no permission beyond what the app already has, plus SET_ALARM (normal). */
class SystemActions(private val app: Context) : SystemApi {
    private val pm: PackageManager = app.packageManager
    private val main = Handler(Looper.getMainLooper())
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val ids = AtomicInteger(4100)
    @Volatile private var appsCache: Pair<Long, List<AppEntry>>? = null

    override fun apps(): List<AppEntry> {
        appsCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 30_000) return list }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(launcher, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(ri.loadLabel(pm).toString().trim().orEmpty().ifEmpty { pkg }, pkg)
            }
            .distinctBy { it.packageName }
        appsCache = System.currentTimeMillis() to list
        return list
    }

    override fun isInstalled(pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun labelOf(pkg: String?): String? = pkg?.let {
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
        } catch (_: Exception) {
            null
        }
    }

    override fun launch(app: AppEntry): SysResult {
        val intent = pm.getLaunchIntentForPackage(app.packageName)
            // l10n:keep agent tool result
            ?: return SysResult(false, "「${app.label}」没有可以打开的界面。")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // l10n:keep agent tool result
        return start(intent, "打开「${app.label}」", "已打开「${app.label}」。", app.packageName, app.label, AppStrings.get(this.app, R.string.phone_notice_open_app, app.label))
    }

    override fun urlTarget(url: String): UrlTarget {
        val intent = viewIntent(url)
        val ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            // l10n:keep agent tool result
            ?: return UrlTarget(error = "没有应用能打开这个链接（可能没装对应的 App）。")
        val pkg = ri.activityInfo?.packageName
        // "android" is the system chooser: the person picks the app.
        return if (pkg == null || pkg == "android") UrlTarget() else UrlTarget(packageName = pkg, appLabel = labelOf(pkg))
    }

    override fun openUrl(url: String, target: UrlTarget): SysResult {
        // l10n:keep agent tool result
        val where = target.appLabel?.let { "（用「$it」）" } ?: ""
        // l10n:keep agent tool result
        return start(viewIntent(url), "打开链接$where", "已打开链接$where。", target.packageName, target.appLabel,
            target.appLabel?.let { AppStrings.get(app, R.string.phone_notice_open_url_in, it) } ?: AppStrings.get(app, R.string.phone_notice_open_url))
    }

    /** ACTION_VIEW + BROWSABLE: only activities that accept links from a browser, never a named component. */
    private fun viewIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply {
            component = null
            selector = null
        }

    override fun intent(spec: IntentSpec, shareTo: AppEntry?): SysResult = when (spec) {
        is IntentSpec.Alarm -> {
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, spec.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, spec.minute)
            spec.message?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (spec.days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(spec.days.map { it % 7 + 1 }))
            val time = "%02d:%02d".format(spec.hour, spec.minute)
            // l10n:keep agent tool result
            val days = if (spec.days.isEmpty()) "" else "，重复：" + spec.days.joinToString("") { "一二三四五六日"[it - 1].toString() }.let { "周$it" }
            // l10n:keep agent tool result
            startResolved(i, "设置闹钟", "已打开时钟设置 $time 的闹钟$days。有的手机直接保存，有的要用户点一下确认。", noticeWhat = AppStrings.get(app, R.string.phone_notice_alarm))
        }
        is IntentSpec.Timer -> {
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, spec.seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            spec.message?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            // l10n:keep agent tool result
            startResolved(i, "设置计时器", "已打开时钟设置 ${spec.seconds} 秒的计时器，请用户点开始。", noticeWhat = AppStrings.get(app, R.string.phone_notice_timer))
        }
        is IntentSpec.Calendar -> {
            val i = Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, spec.beginMs)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, spec.endMs)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, spec.allDay)
                .putExtra(CalendarContract.Events.TITLE, spec.title)
            spec.location?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            spec.description?.let { i.putExtra(CalendarContract.Events.DESCRIPTION, it) }
            // l10n:keep agent tool result
            val fmt = SimpleDateFormat(if (spec.allDay) "M月d日（全天）" else "M月d日 HH:mm", Locale.CHINA)
            // l10n:keep agent tool result
            startResolved(i, "新建日程", "已打开日历新建「${spec.title}」，${fmt.format(Date(spec.beginMs))}。请用户核对后点保存。", noticeWhat = AppStrings.get(app, R.string.phone_notice_calendar))
        }
        is IntentSpec.Dial -> startResolved(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(spec.number))),
            // l10n:keep agent tool result
            "打开拨号盘",
            // l10n:keep agent tool result
            "已在拨号盘填好 ${spec.number}，没有拨出；要打的话由用户点拨号。",
            noticeWhat = AppStrings.get(app, R.string.phone_notice_dial),
        )
        is IntentSpec.SmsDraft -> {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (spec.number?.let { Uri.encode(it) } ?: "")))
                .putExtra("sms_body", spec.text)
            // l10n:keep agent tool result
            startResolved(i, "写短信", "已打开短信草稿" + (spec.number?.let { "（发给 $it）" } ?: "") + "，没有发送；由用户确认后点发送。", noticeWhat = AppStrings.get(app, R.string.phone_notice_sms))
        }
        is IntentSpec.Share -> {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, spec.text)
            if (shareTo != null) {
                send.setPackage(shareTo.packageName)
                // l10n:keep agent tool result
                startResolved(send, "分享到「${shareTo.label}」", "已把文字分享到「${shareTo.label}」，由用户选择联系人并发送。", shareTo.label, AppStrings.get(app, R.string.phone_notice_share_to, shareTo.label))
            } else {
                // l10n:keep agent tool result
                start(Intent.createChooser(send, AppStrings.get(app, R.string.phone_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "分享", "已打开分享面板，由用户选择发给谁。", null, null, AppStrings.get(app, R.string.phone_notice_share))
            }
        }
        is IntentSpec.Navigate -> navigate(spec)
        is IntentSpec.Settings -> settings(spec, shareTo)
    }

    private fun navigate(n: IntentSpec.Navigate): SysResult {
        val candidates = ArrayList<Triple<String, String?, String>>() // uri, package, name
        // l10n:keep agent tool result
        val amap = Triple(NavUris.amap(n), NavUris.AMAP, "高德地图")
        // l10n:keep agent tool result
        val baidu = Triple(NavUris.baidu(n), NavUris.BAIDU, "百度地图")
        when (n.map) {
            "amap" -> candidates += amap
            "baidu" -> candidates += baidu
            else -> {
                candidates += amap
                candidates += baidu
            }
        }
        for ((uri, pkg, name) in candidates) {
            if (pkg != null && !isInstalled(pkg)) continue
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg)
            if (pm.resolveActivity(i, 0) == null) continue
            // l10n:keep agent tool result
            return start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "导航", "已在「$name」打开到「${n.destination ?: "${n.lat},${n.lng}"}」的路线，由用户确认后开始导航。", pkg, name, AppStrings.get(app, R.string.phone_notice_navigate))
        }
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse(NavUris.geo(n)))
        if (pm.resolveActivity(geo, 0) == null) {
            // l10n:keep agent tool result
            return SysResult(false, "手机上没有能导航的地图应用（高德、百度都没装）。")
        }
        // l10n:keep agent tool result
        val note = if (n.map != null) "没装指定的地图，" else ""
        // l10n:keep agent tool result
        return start(geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "导航", "${note}已用地图应用打开「${n.destination ?: "${n.lat},${n.lng}"}」，由用户选路线。", null, null, AppStrings.get(app, R.string.phone_notice_navigate))
    }

    private fun settings(s: IntentSpec.Settings, app: AppEntry?): SysResult {
        val action = when (s.page) {
            "home" -> Settings.ACTION_SETTINGS
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "mobile_network" -> Settings.ACTION_WIRELESS_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "app_notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            // l10n:keep agent tool result
            else -> return SysResult(false, "没有这个设置页。")
        }
        val i = Intent(action)
        if (s.page == "app_notifications") {
            // l10n:keep agent tool result
            val target = app ?: return SysResult(false, "app_notifications 需要 app。")
            i.putExtra(Settings.EXTRA_APP_PACKAGE, target.packageName)
        }
        var intent = i
        if (pm.resolveActivity(intent, 0) == null) {
            if (s.page == "battery") intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            if (pm.resolveActivity(intent, 0) == null) intent = Intent(Settings.ACTION_SETTINGS)
        }
        return start(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            // l10n:keep agent tool result
            "打开设置",
            // l10n:keep agent tool result
            "已打开设置页（${s.page}）。DSH 不会替用户改系统设置：请告诉用户要点哪里。",
            "com.android.settings",
            // l10n:keep agent tool result
            "设置",
            AppStrings.get(this.app, R.string.phone_notice_settings),
        )
    }

    /** Starts [intent] after checking something handles it; the result names the app that does. */
    private fun startResolved(intent: Intent, what: String, done: String, label: String? = null, noticeWhat: String = what): SysResult {
        val ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            // l10n:keep agent tool result
            ?: return SysResult(false, "手机上没有能处理「$what」的应用。")
        val pkg = ri.activityInfo?.packageName?.takeIf { it != "android" }
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), what, done, pkg, label ?: labelOf(pkg), noticeWhat)
    }

    /**
     * Android 10+ blocks activity starts from the background unless the app is visible or its
     * accessibility service is bound. Otherwise we still try, and also post a notification the
     * person can tap (a notification tap always may start it).
     */
    private fun start(intent: Intent, what: String, done: String, pkg: String?, label: String?, noticeWhat: String = what): SysResult {
        try {
            app.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // l10n:keep agent tool result
            return SysResult(false, "手机上没有能处理「$what」的应用。")
        } catch (e: SecurityException) {
            // l10n:keep agent tool result
            return SysResult(false, "系统不允许 DSH 执行「$what」：${e.message ?: "没有权限"}。")
        }
        val exempt = AppVisibility.visible || PhoneRegistry.mainDriver != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (exempt) return SysResult(true, done, pkg, label)
        postOpen(intent, noticeWhat)
        return SysResult(
            true,
            // l10n:keep agent tool result
            "$done（DSH 不在前台，系统可能拦下后台弹出的界面；已发通知「$what」，用户点一下就能打开。）",
            pkg,
            label,
        )
    }

    private fun postOpen(intent: Intent, what: String) {
        ensureChannel(CHANNEL_OPEN, AppStrings.get(app, R.string.phone_open_channel), NotificationManager.IMPORTANCE_HIGH)
        val id = ids.incrementAndGet()
        val pi = PendingIntent.getActivity(app, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(TAG, id, builder(CHANNEL_OPEN).setContentTitle(AppStrings.get(app, R.string.phone_open_notice, what)).setContentText(AppStrings.get(app, R.string.phone_tap_open)).setContentIntent(pi).setAutoCancel(true).build())
    }

    override fun notify(title: String, text: String): SysResult {
        if (!nm.areNotificationsEnabled()) {
            // l10n:keep agent tool result
            return SysResult(false, "DSH 没有通知权限，通知发不出去。请用户在系统设置里允许 DSH 发通知。")
        }
        ensureChannel(CHANNEL_NOTIFY, AppStrings.get(app, R.string.phone_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT)
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = builder(CHANNEL_NOTIFY)
            .setContentTitle(title.take(100))
            .setContentText(text.take(500))
            .setStyle(Notification.BigTextStyle().bigText(text.take(2000)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(TAG, ids.incrementAndGet(), n)
        // l10n:keep agent tool result
        return SysResult(true, "已发通知「${SafetyRules.quoteText(title, 30)}」。")
    }

    override fun clipboardSet(text: String): SysResult {
        // l10n:keep agent tool result
        if (text.length > 100_000) return SysResult(false, "内容太长（超过 10 万字）。")
        val ok = onMain {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DSH", text))
            true
        } == true
        // l10n:keep agent tool result
        return if (ok) SysResult(true, "已写入剪贴板（${text.length} 字）。") else SysResult(false, "写剪贴板失败。")
    }

    override fun clipboardGet(): SysResult {
        val visible = AppVisibility.visible
        val text = onMain {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
        }
        if (text.isNullOrEmpty()) {
            return if (visible || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // l10n:keep agent tool result
                SysResult(true, "剪贴板是空的。")
            } else {
                // l10n:keep agent tool result
                SysResult(false, "读不到剪贴板：系统只允许前台应用读，DSH 不在前台。请用户切回 DSH 再说，或直接把内容发给你。")
            }
        }
        // l10n:keep agent tool result
        val shown = if (text.length > 20_000) text.take(20_000) + "\n…（后面还有 ${text.length - 20_000} 字）" else text
        // l10n:keep agent tool result
        return SysResult(true, "剪贴板内容：\n$shown")
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        var out: T? = null
        val done = CountDownLatch(1)
        main.post {
            out = runCatching(block).getOrNull()
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
        return out
    }

    private fun ensureChannel(id: String, name: String, importance: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }

    private fun builder(channel: String): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(app, channel)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(app)
        }
        return b.setSmallIcon(R.drawable.ic_stat_dsh)
    }

    companion object {
        private const val TAG = "dsh-phone-sys"
        const val CHANNEL_NOTIFY = "dsh-phone-notify"
        const val CHANNEL_OPEN = "dsh-phone-open"
    }
}
