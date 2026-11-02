package com.deepseekharness.app.phone

import com.deepseekharness.app.i18n.AppStrings
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.deepseekharness.app.ui.MainActivity
import com.deepseekharness.app.R
import com.deepseekharness.app.phone.a11y.A11yKeeper
import com.deepseekharness.app.phone.a11y.PhoneA11yService
import com.deepseekharness.app.phone.background.BackgroundControl
import com.deepseekharness.app.phone.background.SecureSettingsGrant
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-wide owner of phone control on Android: the master switch and app lists (prefs), the
 * bridge's lifecycle around the engine (started before DSH launches, stopped when it exits, never
 * in safe mode), the [PhoneEnv] the core runs against, the task's status line, the screen-off
 * stop, and the status pushed to Flutter. [com.deepseekharness.app.core.HarnessController]
 * calls [prepareLaunch] / [launched] / [onEngineReady] / [onEngineStopped].
 */
class PhoneControl private constructor(private val app: Context) : PhoneEnv, ControlListener {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dsh-phone-status").apply { isDaemon = true } }

    val tasks = PhoneTasks()
    val log = ActionLog()
    val system = SystemActions(app)
    private val localizedUi = PhoneUiText { key, args ->
        when (key) {
            "phone_log_clipboard_write" -> return@PhoneUiText AppStrings.plural(app, R.plurals.phone_log_clipboard_write, args[0] as Int, *args.toTypedArray())
            "phone_log_type" -> return@PhoneUiText AppStrings.plural(app, R.plurals.phone_log_type, args[2] as Int, *args.toTypedArray())
        }
        val id = when (key) {
            "phone_waived_status" -> R.string.phone_waived_status
            "phone_move_background" -> R.string.phone_move_background
            "phone_opening_background" -> R.string.phone_opening_background
            "phone_reading_background" -> R.string.phone_reading_background
            "phone_reading_screen" -> R.string.phone_reading_screen
            "phone_reading_list" -> R.string.phone_reading_list
            "phone_acting" -> R.string.phone_acting
            "phone_status_tap" -> R.string.phone_status_tap
            "phone_status_long_tap" -> R.string.phone_status_long_tap
            "phone_status_type" -> R.string.phone_status_type
            "phone_status_scroll" -> R.string.phone_status_scroll
            "phone_status_tap_xy" -> R.string.phone_status_tap_xy
            "phone_status_key" -> R.string.phone_status_key
            "phone_status_press" -> R.string.phone_status_press
            "phone_key_back" -> R.string.phone_key_back
            "phone_key_home" -> R.string.phone_key_home
            "phone_key_recents" -> R.string.phone_key_recents
            "phone_key_notifications" -> R.string.phone_key_notifications
            "phone_confirm_first_app" -> R.string.phone_confirm_first_app
            "phone_confirm_element" -> R.string.phone_confirm_element
            "phone_confirm_tap" -> R.string.phone_confirm_tap
            "phone_confirm_long_tap" -> R.string.phone_confirm_long_tap
            "phone_confirm_type" -> R.string.phone_confirm_type
            "phone_confirm_submit" -> R.string.phone_confirm_submit
            "phone_confirm_tap_xy" -> R.string.phone_confirm_tap_xy
            "phone_confirm_scroll" -> R.string.phone_confirm_scroll
            "phone_confirm_key" -> R.string.phone_confirm_key
            "phone_current_app" -> R.string.phone_current_app
            "phone_log_apps" -> R.string.phone_log_apps
            "phone_log_apps_filtered" -> R.string.phone_log_apps_filtered
            "phone_log_open_url" -> R.string.phone_log_open_url
            "phone_log_open_url_scheme" -> R.string.phone_log_open_url_scheme
            "phone_log_open_app" -> R.string.phone_log_open_app
            "phone_log_open_background" -> R.string.phone_log_open_background
            "phone_log_notify" -> R.string.phone_log_notify
            "phone_log_clipboard_read" -> R.string.phone_log_clipboard_read
            "phone_log_background_read" -> R.string.phone_log_background_read
            "phone_log_screen_read" -> R.string.phone_log_screen_read
            "phone_log_collect" -> R.string.phone_log_collect
            "phone_log_scroll" -> R.string.phone_log_scroll
            "phone_log_tap_xy" -> R.string.phone_log_tap_xy
            "phone_log_key" -> R.string.phone_log_key
            "phone_log_handoff" -> R.string.phone_log_handoff
            "phone_log_long_tap" -> R.string.phone_log_long_tap
            "phone_log_tap" -> R.string.phone_log_tap
            "phone_log_submit" -> R.string.phone_log_submit
            "phone_log_type_secret" -> R.string.phone_log_type_secret
            "phone_role_button" -> R.string.phone_role_button
            "phone_role_input" -> R.string.phone_role_input
            "phone_role_list" -> R.string.phone_role_list
            "phone_role_switch" -> R.string.phone_role_switch
            "phone_role_checkbox" -> R.string.phone_role_checkbox
            "phone_role_radio" -> R.string.phone_role_radio
            "phone_role_slider" -> R.string.phone_role_slider
            "phone_role_dropdown" -> R.string.phone_role_dropdown
            "phone_role_link" -> R.string.phone_role_link
            "phone_role_tab" -> R.string.phone_role_tab
            "phone_role_image" -> R.string.phone_role_image
            "phone_role_text" -> R.string.phone_role_text
            else -> null
        }
        id?.let { AppStrings.get(app, it, *args.toTypedArray()) }
    }

    override fun uiText(): PhoneUiText = localizedUi

    val core = PhoneCore(this, tasks, log)
    val notificationGate = NotificationGate(app) { setStatusLine(it) }
    val prompts = PhonePrompts(app, tasks, ::isEnabled, ::isLocked, notificationGate, ::reopen)
    private val server = McpServer(PhoneToolCatalog.listJson(), core::call, SERVER_VERSION)
    private val bridge = PhoneBridge(server) { line -> logger(line) }

    @Volatile private var logger: (String) -> Unit = {}
    @Volatile private var rootfs: File? = null
    @Volatile private var policiesCache: PhonePolicies? = null
    @Volatile private var statusLine: String? = null
    @Volatile private var lastStatus: Map<String, Any?>? = null
    @Volatile private var keptTask = 0L
    @Volatile private var tickFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val sinks = CopyOnWriteArraySet<(Map<String, Any?>) -> Unit>()
    val background: BackgroundControl = BackgroundControl.get(app)

    init {
        PhoneRegistry.notificationGate = notificationGate
        PhoneRegistry.promptGate = prompts
        PhoneRegistry.controlListener = this
        PhoneRegistry.unlocks = { policies().unlocks }
        background.onStatusChanged = { emitStatus() }
        (app as? Application)?.let { AppVisibility.register(it) }
        tasks.addListener { onTask(it) }
        ticker.execute {
            keep()
            if (isEnabled()) ensureChrootBridge()
        }
    }

    // ── Switch and app lists ────────────────────────────────────────────────────────

    fun isEnabled(): Boolean = switchedOn(app)

    /** Off takes effect at once (bridge closed, task stopped); on needs an engine restart (the UI does it). */
    fun setEnabled(enabled: Boolean): Boolean {
        prefs.edit().putBoolean(K_ENABLED, enabled).apply()
        if (!enabled) {
            // l10n:keep agent stop reason
            tasks.stop("手机操控已关闭")
            stopBridge()
            ticker.execute { syncChrootState(false, null) }
        } else {
            tasks.clearStop()
            ticker.execute {
                keep()
                ensureChrootBridge()
            }
        }
        emitStatus()
        return true
    }

    override fun policies(): PhonePolicies = policiesCache ?: run {
        val stored = prefs.getString(K_POLICIES, null)
        val p = try {
            PhonePolicies.fromMap(stored?.let { Json.parse(it) as? Map<*, *> })
        } catch (_: Exception) {
            PhonePolicies()
        }
        policiesCache = p
        p
    }

    fun setPolicies(map: Map<*, *>?): Boolean {
        // Partial updates keep what they don't mention (the 免确认 switch sends only its flag).
        val p = PhonePolicies.fromMap(map, base = policies())
        prefs.edit().putString(K_POLICIES, Json.write(p.toMap())).apply()
        policiesCache = p
        return true
    }

    /** `getPolicies`: the lists, a label for every listed package, and the shipped defaults. */
    fun policiesMap(): Map<String, Any?> {
        val p = policies()
        val labels = LinkedHashMap<String, String>()
        for (pkg in (p.denyPackages + p.confirmEveryWritePackages + PhonePolicies().denyPackages + PhonePolicies().confirmEveryWritePackages).distinct()) {
            label(pkg)?.let { labels[pkg] = it }
        }
        return p.toMap() + mapOf("labels" to labels, "defaults" to PhonePolicies().toMap())
    }

    private fun label(pkg: String): String? =
        SafetyRules.labelFor(app.packageManager, pkg).takeIf { it != pkg }

    // ── Engine lifecycle (RuntimeController's worker thread) ───────────────────────

    /**
     * Before DSH launches: with the switch on and not in safe mode, writes the guest files, starts
     * the bridge and returns true (the launch then adds the `--patch` overlay). Otherwise makes
     * sure nothing is listening and returns false: DSH mounts no phone tools at all.
     */
    fun prepareLaunch(rootfs: File, safe: Boolean, log: (String) -> Unit): Boolean {
        logger = log
        tasks.invalidateOwners()
        stopBridge()
        this.rootfs = rootfs
        if (!isEnabled() || safe) {
            PhoneGuest.clearConfig(rootfs)
            if (isEnabled()) log("[dsh-app] phone control stays off in safe mode")
            emitStatus()
            return false
        }
        return try {
            PhoneGuest.writeFiles(rootfs)
            val ep = bridge.start(withTcp = prefs.getBoolean(K_TCP_FALLBACK, false))
            PhoneGuest.writeConfig(rootfs, ep)
            registerScreenOff()
            tasks.clearStop()
            emitStatus()
            true
        } catch (e: Exception) {
            log("[dsh-app] phone control: setup failed (${e.message}); starting without phone tools")
            stopBridge()
            false
        }
    }

    /** After the launch command was built: [mounted] false means this runtime's template had no `dsh web` to add the overlay to. */
    fun launched(mounted: Boolean) {
        if (mounted) return
        logger("[dsh-app] phone control: this runtime's launch command has no `dsh web` to add the phone overlay to; phone tools are off")
        stopBridge()
    }

    /**
     * DSH printed its login line, so every plugin (including the MCP client's first connection)
     * has loaded. No authenticated connection 5 s later means the guest couldn't reach the abstract socket:
     * open the loopback fallback now (the client's reconnect re-reads bridge.json) and keep it on
     * for later launches.
     */
    fun onEngineReady() {
        // Grace period: the client's first connection normally lands before the login line anyway.
        ticker.schedule({ checkReached() }, 5, TimeUnit.SECONDS)
    }

    private fun checkReached() {
        if (!bridge.listening || bridge.authenticatedConnections > 0) return
        val ep = bridge.endpoint ?: return
        if (ep.tcpPort != null) {
            logger("[dsh-app] phone control: DSH did not connect to the phone bridge (unix socket nor loopback)")
            return
        }
        logger("[dsh-app] phone control: DSH did not reach the unix socket; adding the loopback fallback")
        try {
            val next = bridge.addTcp() ?: return
            rootfs?.let { PhoneGuest.writeConfig(it, next) }
            prefs.edit().putBoolean(K_TCP_FALLBACK, true).apply()
        } catch (e: Exception) {
            logger("[dsh-app] phone control: loopback fallback failed: ${e.message}")
        }
        emitStatus()
    }

    fun onEngineStopped() {
        tasks.invalidateOwners()
        stopBridge()
        core.worker.retire()
        tasks.endTask()
        emitStatus()
    }

    private fun stopBridge() {
        bridge.stop()
        rootfs?.let { PhoneGuest.clearConfig(it) }
        unregisterScreenOff()
    }

    val bridgeListening: Boolean get() = bridge.listening

    // ── Task ────────────────────────────────────────────────────────────────────────

    fun stopTask(reason: String): Boolean {
        tasks.stop(reason)
        return true
    }

    /**
     * Dart (`finishTask`): the turn that drove the phone ended. Ends the task — a stop's hold too —
     * and closes the overlay, ball and cards included. False when there was nothing to end, or a
     * phone call still runs, or the requested task changed. Any thread.
     */
    fun finishTask(expectedTaskId: Long? = null): Boolean {
        val taskId = expectedTaskId ?: tasks.snapshot().id
        return tasks.finish(taskId)
    }

    fun finishTaskScope(expectedTaskId: Long): PhoneTasks.Owner? = tasks.finishScope(expectedTaskId)

    /**
     * Brings the app forward on [sessionId] (just the app when null) after a finished task. Started
     * from the accessibility service when bound. This is a best-effort start under Android's
     * background-start restrictions, not a guarantee. False when start threw; the caller must
     * separately observe its ActivityAware host resumed (some vendors block silently).
     */
    fun reopen(sessionId: String?, host: String?): Boolean {
        val intent = com.deepseekharness.app.ui.QuickChatSheetActivity.createLaunchIntent(app)
        val from: Context = PhoneA11yService.instance ?: app
        return try {
            from.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun onTask(s: PhoneTasks.Snapshot) {
        prompts.check()
        if (s.active) {
            if (tickFuture == null || tickFuture?.isDone == true) {
                tickFuture = ticker.scheduleWithFixedDelay({ tick() }, 2, 2, TimeUnit.SECONDS)
            }
            if (s.id != keptTask) {
                keptTask = s.id
                ticker.execute(::keep)
            }
        }
        if (!s.active) {
            tickFuture?.cancel(false)
            tickFuture = null
            tasks.runIfInactive(s) {
                if (s.stopped && tasks.isStopped()) core.worker.interruptCurrent()
                statusLine = null
            }
            runCatching { PhoneRegistry.overlayGate?.close { cleanup -> tasks.runIfInactive(s, cleanup) } }
            // One virtual display per task: release it when the task ends.
            // IPC must stay outside the PhoneTasks lock; the background owner
            // check and release are serialized with its next registration.
            runCatching { background.endTask(s.id) }
        }
        runCatching { com.deepseekharness.app.HarnessService.checkAndSyncService(app) }
        emitStatus()
    }

    private fun setStatusLine(text: String?) {
        if (statusLine == text) return
        statusLine = text
        runCatching { com.deepseekharness.app.HarnessService.checkAndSyncService(app) }
    }

    /** What the live activity shows while a task runs: what DSH does now, in which app; null otherwise. */
    fun liveTask(): LiveTask? {
        val s = tasks.snapshot()
        if (!s.active) return null
        return LiveTask(s.id, statusLine, s.app, s.paused)
    }

    data class LiveTask(val id: Long, val line: String?, val app: String?, val paused: Boolean)

    /** The engine notification's text while a task runs (with a 停止操作 button); null otherwise. */
    fun foregroundLine(): String? {
        val s = tasks.snapshot()
        if (!s.active) return null
        return statusLine ?: AppStrings.get(app, R.string.phone_running) + (s.app?.let { " · $it" } ?: "")
    }

    private fun registerScreenOff() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                // l10n:keep agent stop reason
                onStopRequested("熄屏")
                if (PhoneRegistry.backgroundDriver != null) runCatching { background.interrupt() }
            }
        }
        screenReceiver = r
        runCatching { app.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
    }

    private fun unregisterScreenOff() {
        screenReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        screenReceiver = null
    }

    // ── Things the plugin asks for ──────────────────────────────────────────────────

    fun a11yEnabledInSettings(): Boolean = PhoneA11yService.isEnabled(app)

    fun accessibilitySettingsIntent(): Intent {
        val component = PhoneA11yService.component(app).flattenToString()
        // Undocumented but honoured by AOSP Settings and several ROMs: highlight our row.
        val args = Bundle().apply { putString(":settings:fragment_args_key", component) }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", component)
            .putExtra(":settings:show_fragment_args", args)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun appDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 「读一下当前屏幕」: the person's own on-device check, never sent to a model, so it shows
     * whatever is in front (DSH's own settings page included; the own-app rule is for tool calls).
     */
    fun testScreen(): Map<String, Any?> {
        val driver = PhoneRegistry.mainDriver
            ?: return mapOf("ok" to false, "reason" to if (a11yEnabledInSettings()) AppStrings.get(app, R.string.phone_a11y_connecting) else AppStrings.get(app, R.string.phone_a11y_off))
        val outcome = core.worker.run(8_000, 15_000) {
            val av = driver.availability()
            if (!av.ok) null to (av.userReason ?: av.reason ?: AppStrings.get(app, R.string.phone_a11y_unavailable)) else driver.observe(detail = false, image = false) to null
        }
        return when (outcome) {
            is SerialWorker.Outcome.Done -> {
                val (snap, reason) = outcome.value
                if (snap == null) mapOf("ok" to false, "reason" to reason) else mapOf("ok" to true, "text" to snap.text)
            }
            is SerialWorker.Outcome.TimedOut -> mapOf("ok" to false, "reason" to if (outcome.queued) AppStrings.get(app, R.string.phone_busy) else AppStrings.get(app, R.string.phone_read_timeout))
            is SerialWorker.Outcome.Failed -> mapOf("ok" to false, "reason" to (outcome.error.message ?: outcome.error.javaClass.simpleName))
            SerialWorker.Outcome.Interrupted -> mapOf("ok" to false, "reason" to AppStrings.get(app, R.string.phone_stopped))
        }
    }

    fun status(): Map<String, Any?> = linkedMapOf(
        "enabled" to isEnabled(),
        "bridgeListening" to bridge.listening,
        "a11y" to linkedMapOf("enabled" to a11yEnabledInSettings(), "serviceConnected" to (PhoneRegistry.mainDriver != null)),
        "overlay" to (PhoneRegistry.overlayGate != null),
        "notifications" to nm.areNotificationsEnabled(),
        "confirmationNotifications" to notificationGate.available(),
        "background" to backgroundStatus(),
        "a11yKeeper" to keeperStatus(),
        "task" to tasks.snapshot().toMap(),
        "device" to linkedMapOf(
            "manufacturer" to Build.MANUFACTURER,
            "sdkInt" to Build.VERSION.SDK_INT,
            "sideloaded" to sideloaded(),
            "restricted" to RestrictedSettings.read(app),
        ),
    )

    private fun backgroundStatus(): Map<String, Any?> = try {
        background.status()
    } catch (e: Exception) {
        linkedMapOf("mode" to "off", "ready" to false, "reason" to (e.message ?: AppStrings.get(app, R.string.phone_background_error)), "androidOk" to (Build.VERSION.SDK_INT >= 34))
    }

    /** 自动恢复无障碍: whether it may work (the permission), the one-tap way to it, the command for the other. */
    private fun keeperStatus(): Map<String, Any?> {
        val granted = A11yKeeper.granted(app)
        return linkedMapOf(
            "granted" to granted,
            "path" to runCatching { background.grantPath(granted) }.getOrDefault(SecureSettingsGrant.SETUP),
            "command" to SecureSettingsGrant.command(app.packageName),
        )
    }

    /**
     * 自动恢复无障碍 → the one tap: the shell-uid engine grants WRITE_SECURE_SETTINGS over Shizuku or
     * self-ADB. Shizuku without its own permission asks for that first (`pending`) and grants once
     * the person allows. Blocks; the plugin thread.
     */
    fun grantSecureSettings(): Map<String, Any?> = when (val path = background.grantPath(A11yKeeper.granted(app))) {
        SecureSettingsGrant.GRANTED -> mapOf("ok" to true)
        SecureSettingsGrant.SHIZUKU, SecureSettingsGrant.ADB -> {
            background.grantSecureSettings(path)
            val ok = A11yKeeper.granted(app)
            if (ok) ticker.execute(::keep)
            emitStatus()
            val failed = if (path == SecureSettingsGrant.ADB) R.string.background_adb_disconnected else R.string.a11y_keeper_grant_failed
            mapOf("ok" to ok, "reason" to if (ok) null else AppStrings.get(app, failed))
        }
        SecureSettingsGrant.SHIZUKU_PERMISSION -> {
            val asked = background.requestShizukuPermission { ticker.execute { grantSecureSettings() } }
            mapOf("ok" to false, "pending" to asked, "reason" to if (asked) null else AppStrings.get(app, R.string.background_shizuku_permission))
        }
        else -> mapOf("ok" to false, "reason" to AppStrings.get(app, R.string.a11y_keeper_needs_setup))
    }

    private fun keep() {
        runCatching { A11yKeeper.check(app) }
    }

    /** Installed from a browser or file manager rather than a store (Android 13+ restricted settings apply). */
    private fun sideloaded(): Boolean? = try {
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") app.packageManager.getInstallerPackageName(app.packageName)
        }
        installer == null || installer !in STORES
    } catch (_: Exception) {
        null
    }

    fun addSink(sink: (Map<String, Any?>) -> Unit) {
        sinks += sink
        ticker.execute { pushIfChanged(force = true) }
    }

    fun removeSink(sink: (Map<String, Any?>) -> Unit) {
        sinks -= sink
    }

    /** Recomputes the status off the main thread and pushes it if it changed. */
    fun emitStatus() {
        if (sinks.isEmpty()) return
        ticker.execute { pushIfChanged(force = false) }
    }

    private fun pushIfChanged(force: Boolean) {
        if (sinks.isEmpty()) return
        val s = try {
            status()
        } catch (_: Exception) {
            return
        }
        if (!force && s == lastStatus) return
        lastStatus = s
        main.post { sinks.forEach { runCatching { it(s) } } }
    }

    /** Every 2 s: end idle tasks; 自动恢复无障碍; push status changes we can't observe (accessibility connecting). */
    private fun tick() {
        try {
            tasks.expireIdle()
            A11yKeeper.check(app)
            pushIfChanged(force = false)
        } catch (_: Exception) {
        }
    }

    // ── ControlListener (the overlay's 暂停 / 停止 / 插话, the service's own stops) ──

    override fun onPauseChanged(paused: Boolean) = tasks.setPaused(paused)

    override fun onStopRequested(reason: String) {
        // The service also reports screen off / service gone while idle: only a running task stops.
        if (tasks.snapshot().active) tasks.stop(reason) else tasks.invalidateOwners()
    }

    override fun onInterject(text: String) = tasks.interject(text)

    override fun onAccessibilityChanged(connected: Boolean) = emitStatus()

    // ── PhoneEnv ────────────────────────────────────────────────────────────────────

    override fun enabled(): Boolean = isEnabled()

    override fun isLocked(): Boolean {
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return km.isKeyguardLocked || !power.isInteractive
    }

    override fun gate(): UserGate = prompts

    /**
     * The background driver is registered per task: bring it up on first use (worker thread; may
     * connect). The main one may be on its way back (自动恢复无障碍): give it a few seconds.
     */
    override fun driver(screen: PhoneScreen): PhoneDriver? {
        if (screen == PhoneScreen.MAIN && PhoneRegistry.mainDriver == null && A11yKeeper.check(app)) {
            val until = SystemClock.elapsedRealtime() + KEEPER_WAIT_MS
            while (PhoneRegistry.mainDriver == null && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        }
        if (screen != PhoneScreen.BACKGROUND) return PhoneRegistry.driver(screen)
        return if (runCatching { background.beginTask(tasks.snapshot().id) }.getOrDefault(false)) PhoneRegistry.backgroundDriver else null
    }

    override fun beforeHandoff(screen: PhoneScreen): String? {
        if (screen != PhoneScreen.BACKGROUND || PhoneRegistry.backgroundDriver == null) return null
        runCatching { background.endTask() }
        // l10n:keep agent stop reason
        return "后台屏已释放，App 回到了主屏，用户在主屏上操作。之后请用主屏（s 开头的屏幕号）。"
    }

    override fun a11yState(): A11yState = when {
        PhoneRegistry.mainDriver != null -> A11yState.CONNECTED
        a11yEnabledInSettings() -> A11yState.ENABLED_NOT_CONNECTED
        else -> A11yState.DISABLED
    }

    override fun backgroundUnavailable(): String {
        val reason = (backgroundStatus()["reason"] as? String)?.let { "（$it）" } ?: ""
        // l10n:keep agent stop reason
        return "不打扰模式（后台屏）用不了$reason。只能在主屏操作；要用后台屏请用户到 设置 → 手机操控 → 不打扰模式 开通。"
    }

    override fun system(): SystemApi = system

    override fun zone(): ZoneId = ZoneId.systemDefault()

    override fun notifyFix(kind: String) {
        if (!tasks.firstNotice(kind)) return
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_FIX, AppStrings.get(app, R.string.phone_fix_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val pi = PendingIntent.getActivity(app, 0, accessibilitySettingsIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(app, CHANNEL_FIX) else @Suppress("DEPRECATION") Notification.Builder(app)
        val text = if (a11yEnabledInSettings()) AppStrings.get(app, R.string.phone_a11y_disconnected_notice) else AppStrings.get(app, R.string.phone_a11y_enable_notice)
        nm.notify(
            NOTIFY_FIX_ID,
            b.setSmallIcon(R.drawable.ic_stat_dsh).setContentTitle(AppStrings.get(app, R.string.phone_a11y_title)).setContentText(text)
                .setContentIntent(pi).setAutoCancel(true).build(),
        )
    }


    /**
     * Starts the MCP bridge for DSHA Native chroot and writes bridge.json + relay.cjs + phone.patch.yml
     * into both app private storage and /data/adb/dsha/rootfs so chroot DSH can connect at any time.
     */
    @Synchronized
    fun ensureChrootBridge(): Boolean {
        if (!isEnabled()) {
            stopBridge()
            syncChrootState(false, null)
            return false
        }
        val ep = if (bridge.listening && bridge.endpoint != null) {
            bridge.endpoint!!
        } else {
            bridge.start(withTcp = true)
        }
        registerScreenOff()
        syncChrootState(true, ep)
        emitStatus()
        return true
    }

    private fun syncChrootState(enabled: Boolean, ep: PhoneGuest.Endpoint?) {
        try {
            val localDir = File(app.filesDir, "phone")
            localDir.mkdirs()
            val cfgJson = if (enabled && ep != null) PhoneGuest.config(ep) else ""
            if (enabled && ep != null) {
                File(localDir, "bridge.json").writeText(cfgJson)
                File(localDir, "relay.cjs").writeText(PhoneGuest.RELAY_JS)
                File(localDir, "phone.patch.yml").writeText(PhoneGuest.PATCH_YAML)
            } else {
                File(localDir, "bridge.json").delete()
            }
            val cmd = if (enabled && ep != null) {
                val srcDir = localDir.absolutePath
                "mkdir -p /data/adb/dsha/run /data/adb/dsha/rootfs/opt/dsh-app/phone /data/adb/dsha/rootfs/var/lib/dsh-app/phone 2>/dev/null; " +
                    "rm -f /data/adb/dsha/run/phone_control_disabled 2>/dev/null; " +
                    "echo 1 > /data/adb/dsha/run/phone_control_enabled; " +
                    "cp -f '$srcDir/relay.cjs' /data/adb/dsha/rootfs/opt/dsh-app/phone/relay.cjs; " +
                    "cp -f '$srcDir/phone.patch.yml' /data/adb/dsha/rootfs/opt/dsh-app/phone/phone.patch.yml; " +
                    "cp -f '$srcDir/bridge.json' /data/adb/dsha/rootfs/var/lib/dsh-app/phone/bridge.json; " +
                    "chmod 644 /data/adb/dsha/rootfs/opt/dsh-app/phone/relay.cjs /data/adb/dsha/rootfs/opt/dsh-app/phone/phone.patch.yml 2>/dev/null; " +
                    "chmod 600 /data/adb/dsha/rootfs/var/lib/dsh-app/phone/bridge.json 2>/dev/null"
            } else {
                "mkdir -p /data/adb/dsha/run 2>/dev/null; " +
                    "echo 1 > /data/adb/dsha/run/phone_control_disabled; " +
                    "rm -f /data/adb/dsha/run/phone_control_enabled /data/adb/dsha/rootfs/var/lib/dsh-app/phone/bridge.json 2>/dev/null"
            }
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val SERVER_VERSION = "1"
        private const val PREFS = "dsh_phone"
        private const val K_ENABLED = "enabled"
        private const val K_POLICIES = "policies"
        private const val K_TCP_FALLBACK = "tcpFallback"
        private const val CHANNEL_FIX = "dsh-phone-fix"
        private const val NOTIFY_FIX_ID = 4099

        /** How long a call needing the main screen waits for 自动恢复无障碍 to bring it back. */
        private const val KEEPER_WAIT_MS = 3_000L

        /** Installer packages of app stores; anything else (or none) counts as sideloaded. */
        private val STORES = setOf(
            "com.android.vending", "com.xiaomi.market", "com.heytap.market", "com.oppo.market", "com.bbk.appstore",
            "com.huawei.appmarket", "com.hihonor.appmarket", "com.tencent.android.qqdownloader", "com.baidu.appsearch",
            "com.wandoujia.phoenix2", "com.qihoo.appstore", "com.sec.android.app.samsungapps", "com.meizu.mstore",
            "com.smartisanos.appstore", "zte.com.market", "com.lenovo.leos.appstore", "com.coolapk.market",
        )

        @Volatile private var instance: PhoneControl? = null

        @JvmStatic
        fun get(context: Context): PhoneControl =
            instance ?: synchronized(this) {
                instance ?: PhoneControl(context.applicationContext).also { instance = it }
            }

        /** Without creating it (DshService builds notifications before anything else may exist). */
        @JvmStatic
        fun peek(): PhoneControl? = instance

        /** The master switch 「手机操控」, without creating the controller. */
        @JvmStatic
        fun switchedOn(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ENABLED, true)
    }
}
