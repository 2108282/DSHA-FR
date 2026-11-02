package com.deepseekharness.app.phone

import java.time.ZoneId

enum class A11yState { CONNECTED, ENABLED_NOT_CONNECTED, DISABLED }

/** What the core needs from the device; [PhoneControl] on Android, a fake in the JVM tests. */
interface PhoneEnv {
    /** The master switch. */
    fun enabled(): Boolean
    fun policies(): PhonePolicies
    fun isLocked(): Boolean
    fun gate(): UserGate?
    fun driver(screen: PhoneScreen): PhoneDriver?
    fun a11yState(): A11yState
    /** Why the background display can't be used, as a sentence for the model. */
    fun backgroundUnavailable(): String
    fun system(): SystemApi
    fun uiText(): PhoneUiText = PhoneUiText.DEFAULT
    fun zone(): ZoneId
    /** Tells the person what to fix (accessibility off, …); at most once per task and kind. */
    fun notifyFix(kind: String)
    /**
     * Before `handoff` while the agent works on [screen]: for the background display, release it
     * without destroying its content so the app comes to the main screen. A sentence for the model, or null.
     */
    fun beforeHandoff(screen: PhoneScreen): String? = null
    fun sleep(ms: Long) = Thread.sleep(ms)
}

/**
 * Runs the fixed tool set: argument checks, screen-id routing (`s` main / `b` background,
 * never falling back across screens), the safety rules, one serial worker with per-call budgets,
 * the post-action screen, and the action log. [call] is what the MCP server invokes.
 */
class PhoneCore(
    private val env: PhoneEnv,
    val tasks: PhoneTasks,
    val log: ActionLog,
    val worker: SerialWorker = SerialWorker("dsh-phone"),
) {
    private val ui: PhoneUiText get() = env.uiText()
    val safety = SafetyGate(env::policies, tasks, env::gate, uiText = env.uiText())

    /** The overlay's status line; 免确认 stays visible the whole time nothing is asked. */
    private fun say(text: String) {
        env.gate()?.status(if (safety.waived()) ui.get("phone_waived_status", "免确认 · %1\$s", text) else text) // l10n:keep test default
    }

    /** The screen of the last screen-based call, for `handoff`. */
    @Volatile private var lastScreen: PhoneScreen = PhoneScreen.MAIN

    /**
     * The task's last hard refusal (密码框, 系统设置, 不碰名单), for the `handoff` that usually
     * follows it: the card says which rule it was. Cleared by a write that went through, a new
     * task, or the handoff it named.
     */
    @Volatile private var refusal: Pair<PhoneTasks.Owner, HandoffCause>? = null

    private class Out(
        val text: String,
        val error: Boolean = false,
        val screen: ScreenSnapshot? = null,
        val app: String? = null,
        val summary: String? = null,
        val decision: String? = null,
    )

    fun call(name: String, args: Map<String, Any?>): ToolOutput {
        // In flight from here until the result goes back: no turn ends while a call runs (PhoneTasks.finish).
        tasks.callStarted()
        try {
            return runCall(name, args)
        } finally {
            tasks.callEnded()
        }
    }

    private fun runCall(name: String, args: Map<String, Any?>): ToolOutput {
        if (name !in PhoneToolCatalog.names) {
            // l10n:keep agent tool result
            return ToolOutput("没有这个手机工具：$name。可用：${PhoneToolCatalog.names.joinToString("、")}", isError = true)
        }
        if (!env.enabled()) {
            // l10n:keep agent tool result
            return ToolOutput("手机操控已在 DSH 设置里关闭。请用户在 设置 → 手机操控 里打开后重启引擎。", isError = true)
        }
        val outcome = worker.run(budgetMs(name), MAX_TOTAL_MS) { scope -> execute(name, Args(args), scope) }
        tasks.touch()
        val out = when (outcome) {
            is SerialWorker.Outcome.Done -> outcome.value
            is SerialWorker.Outcome.TimedOut -> {
                val text = if (outcome.queued) {
                    // l10n:keep agent tool result
                    "前面的手机操作还没结束，这一步没有执行。稍后再试。"
                } else {
                    // l10n:keep agent tool result
                    "这一步超时了，可能执行了也可能没有。不要自动重试同一个动作：先用 screen 看看现在的屏幕。"
                }
                Out(text, error = true, summary = name, decision = "timeout")
            }
            SerialWorker.Outcome.Interrupted -> Out(PhoneTasks.STOPPED_MESSAGE + "。", error = true, summary = name, decision = "stopped")
            is SerialWorker.Outcome.Failed -> Out(
                // l10n:keep agent tool result
                "手机操作出错：${outcome.error.javaClass.simpleName}${outcome.error.message?.let { "：$it" } ?: ""}",
                error = true,
                summary = name,
                decision = "error",
            )
        }
        out.summary?.let { log.add(name, out.app, it, out.decision ?: if (out.error) "error" else "ok") }
        noteRefusal(name, out.decision)
        val interjections = tasks.drainInterjections()
        val text = buildString {
            // l10n:keep agent tool result
            for (line in interjections) append("用户插话：「").append(line).append("」\n\n")
            append(out.text)
            out.screen?.let { if (out.text.isNotEmpty()) append("\n\n"); append(it.text) }
        }
        val image = out.screen?.image
        return ToolOutput(text, out.error, image, if (image != null) out.screen.imageMime else null)
    }

    private fun noteRefusal(tool: String, decision: String?) {
        val owner = tasks.activeOwner() ?: run { refusal = null; return }
        val cause = HandoffCause.ofDecision(decision)
        if (cause != null) {
            refusal = owner to cause
        } else if (tool in WRITES && decision in DONE) {
            refusal = null
        }
    }

    private fun execute(name: String, a: Args, scope: SerialWorker.Scope): Out {
        when (val b = tasks.begin()) {
            is PhoneTasks.Begin.Refused -> return Out(b.message + "。", error = true, summary = name, decision = "stopped")
            is PhoneTasks.Begin.Go -> Unit
        }
        if (tasks.isPaused) {
            val go = scope.userWait { tasks.awaitResume(PAUSE_WAIT_MS) }
            if (!go) {
                // Stopped, or the task ended under the wait (the engine went away): never go on.
                return if (tasks.isStopped() || !tasks.isActive) {
                    Out(PhoneTasks.STOPPED_MESSAGE + "。", error = true, summary = name, decision = "stopped")
                } else {
                    // l10n:keep agent tool result
                    Out("用户暂停了手机操作，超过 10 分钟没有恢复。停下来，告诉用户可以恢复后再继续。", error = true, summary = name, decision = "paused")
                }
            }
        }
        return when (name) {
            PhoneToolCatalog.APPS -> apps(a)
            PhoneToolCatalog.OPEN -> open(a, scope)
            PhoneToolCatalog.INTENT -> intent(a)
            PhoneToolCatalog.NOTIFY -> notify(a)
            PhoneToolCatalog.CLIPBOARD -> clipboard(a)
            PhoneToolCatalog.SCREEN -> screen(a)
            PhoneToolCatalog.COLLECT -> collect(a)
            PhoneToolCatalog.TAP, PhoneToolCatalog.TYPE, PhoneToolCatalog.SCROLL, PhoneToolCatalog.TAP_XY -> act(name, a, scope)
            PhoneToolCatalog.KEY -> key(a)
            PhoneToolCatalog.HANDOFF -> handoff(a, scope)
            // l10n:keep agent tool result
            else -> Out("没有这个手机工具：$name", error = true)
        }
    }

    // ── System actions ──────────────────────────────────────────────────────────────

    private fun apps(a: Args): Out {
        val q = a.str("query")
        val all = env.system().apps().filter { it.packageName != SafetyRules.OWN_PACKAGE }.sortedBy { it.label }
        val hits = AppMatch.filter(all, q)
        if (hits.isEmpty()) return Out(if (q == null) "没读到应用列表。" else "没有找到名称或包名含「$q」的应用。", summary = ui.get("phone_log_apps", "列出应用")) // l10n:keep test default
        val shown = hits.take(MAX_APPS)
        val text = buildString {
            // l10n:keep agent tool result
            append(if (q == null) "共 ${hits.size} 个应用" else "匹配「$q」的应用 ${hits.size} 个").append("（名称 · 包名）：\n")
            shown.forEach { append(it.label).append(" · ").append(it.packageName).append('\n') }
            // l10n:keep agent tool result
            if (hits.size > shown.size) append("…另有 ${hits.size - shown.size} 个，用 query 缩小范围\n")
        }.trimEnd()
        return Out(text, summary = if (q == null) ui.get("phone_log_apps", "列出应用") else ui.get("phone_log_apps_filtered", "列出应用（筛选）")) // l10n:keep test default
    }

    private fun resolveApp(query: String): Either<String, AppEntry> =
        when (val r = AppMatch.resolve(env.system().apps(), query)) {
            is AppMatch.Result.Found -> Either.Right(r.app)
            is AppMatch.Result.Ambiguous -> Either.Left(
                // l10n:keep agent tool result
                "「$query」对应多个应用，请用包名指定：" + r.candidates.joinToString("；") { "${it.label} · ${it.packageName}" },
            )
            // l10n:keep agent tool result
            AppMatch.Result.NotFound -> Either.Left("没找到应用「$query」。用 apps 查一下名称或包名。")
        }

    private fun open(a: Args, scope: SerialWorker.Scope): Out {
        val owner = tasks.activeOwner() ?: return stoppedOut("open")
        val appQ = a.str("app")
        val url = a.str("url")
        val background = a.bool("background") == true
        // l10n:keep agent tool result
        if ((appQ == null) == (url == null)) return Out("open 需要 app 或 url，二选一。", error = true)
        if (env.isLocked()) return locked("open")
        if (url != null) {
            // l10n:keep agent tool result
            if (background) return Out("后台屏只能用 app 打开应用，不支持链接。", error = true)
            UrlPolicy.check(url)?.let { return Out(it, error = true, summary = ui.get("phone_log_open_url", "打开链接"), decision = "refused:url") } // l10n:keep test default
            val target = env.system().urlTarget(url)
            target.error?.let { return Out(it, error = true, summary = ui.get("phone_log_open_url", "打开链接"), decision = "refused:url") } // l10n:keep test default
            target.packageName?.let { pkg ->
                safety.checkOpen(pkg, target.appLabel)?.let { return Out(it.message, error = true, app = target.appLabel ?: pkg, summary = ui.get("phone_log_open_url", "打开链接"), decision = "refused:${it.rule}") } // l10n:keep test default
            }
            val r = env.system().openUrl(url, target)
            return afterSystem(r, ui.get("phone_log_open_url_scheme", "打开链接 %1\$s:", url.substringBefore(':')), target.appLabel ?: target.packageName) // l10n:keep test default
        }
        val app = when (val r = resolveApp(appQ!!)) {
            is Either.Left -> return Out(r.value, error = true)
            is Either.Right -> r.value
        }
        safety.checkOpen(app.packageName, app.label)?.let {
            return Out(it.message, error = true, app = app.label, summary = ui.get("phone_log_open_app", "打开 %1\$s", app.packageName), decision = "refused:${it.rule}") // l10n:keep test default
        }
        if (!background) {
            return afterSystem(env.system().launch(app), ui.get("phone_log_open_app", "打开 %1\$s", app.packageName), app.label) // l10n:keep test default
        }
        val driver = env.driver(PhoneScreen.BACKGROUND)
            ?: return Out(env.backgroundUnavailable(), error = true, app = app.label, summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName), decision = "unavailable") // l10n:keep test default
        driver.availability().let { if (!it.ok) return Out(it.reason ?: "后台屏现在不可用。", error = true, summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName), decision = "unavailable") } // l10n:keep test default
        val mainFront = runCatching { env.driver(PhoneScreen.MAIN)?.foregroundPackage() }.getOrNull()
        var decision = "allowed"
        if (mainFront == app.packageName) {
            val v = safety.confirmation(ui.get("phone_move_background", "这会把「%1\$s」从你的屏幕挪到后台屏，让 DSH 在那里操作。", app.label), app.packageName, app.label, "move-to-background") // l10n:keep test default
            when (val r = safety.resolve(v, scope)) {
                is SafetyGate.Result.No -> return Out(r.message, error = true, app = app.label, summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName), decision = r.decision) // l10n:keep test default
                is SafetyGate.Result.Go -> decision = r.decision
            }
        }
        if (!tasks.isCurrent(owner) || Thread.currentThread().isInterrupted) return stoppedOut("open")
        lastScreen = PhoneScreen.BACKGROUND
        say(ui.get("phone_opening_background", "DSH 正在后台屏打开「%1\$s」", app.label)) // l10n:keep test default
        val res = driver.open(app.packageName)
        tasks.setApp(app.label)
        return Out(res.message, error = !res.ok, screen = res.screen?.let { safety.filterScreen(it) }, app = app.label,
            summary = ui.get("phone_log_open_background", "后台屏打开 %1\$s", app.packageName), decision = if (res.ok) decision else "failed") // l10n:keep test default
    }

    /** A system action that brought up another app's UI: attach the new main screen when we can read it. */
    private fun afterSystem(r: SysResult, summary: String, app: String?): Out {
        if (r.ok) tasks.setApp(r.appLabel ?: app)
        val screen = if (r.ok) mainScreenAfter(SETTLE_AFTER_LAUNCH_MS) else null
        return Out(r.message, error = !r.ok, screen = screen, app = r.appLabel ?: app, summary = summary, decision = if (r.ok) "ok" else "failed")
    }

    private fun mainScreenAfter(settleMs: Long): ScreenSnapshot? {
        val d = env.driver(PhoneScreen.MAIN) ?: return null
        if (!d.availability().ok) return null
        return try {
            env.sleep(settleMs)
            safety.filterScreen(d.observe(detail = false, image = false))
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun intent(a: Args): Out {
        val spec = when (val p = IntentSpec.parse(a, env.zone())) {
            is Either.Left -> return Out(p.value, error = true)
            is Either.Right -> p.value
        }
        val kind = a.str("kind") ?: ""
        if (env.isLocked()) return locked("intent $kind")
        var shareTo: AppEntry? = null
        val appQuery = when (spec) {
            is IntentSpec.Share -> spec.app
            is IntentSpec.Settings -> spec.app
            else -> null
        }
        if (appQuery != null) {
            shareTo = when (val r = resolveApp(appQuery)) {
                is Either.Left -> return Out(r.value, error = true)
                is Either.Right -> r.value
            }
            if (spec is IntentSpec.Share) {
                safety.checkOpen(shareTo.packageName, shareTo.label)?.let {
                    return Out(it.message, error = true, app = shareTo.label, summary = "intent $kind", decision = "refused:${it.rule}")
                }
            }
        }
        val r = env.system().intent(spec, shareTo)
        return afterSystem(r, "intent $kind", r.appLabel ?: shareTo?.label)
    }

    private fun notify(a: Args): Out {
        // l10n:keep agent tool result
        val title = a.str("title") ?: return Out("notify 需要 title 和 text。", error = true)
        // l10n:keep agent tool result
        val text = a.str("text") ?: return Out("notify 需要 title 和 text。", error = true)
        val r = env.system().notify(title, text)
        return Out(r.message, error = !r.ok, summary = ui.get("phone_log_notify", "通知"), decision = if (r.ok) "ok" else "failed") // l10n:keep test default
    }

    private fun clipboard(a: Args): Out {
        val set = a.text("set")
        val get = a.bool("get") == true
        // l10n:keep agent tool result
        if (set != null && get) return Out("clipboard 的 set 和 get 一次只能用一个。", error = true)
        if (set != null) {
            val r = env.system().clipboardSet(set)
            return Out(r.message, error = !r.ok, summary = ui.get("phone_log_clipboard_write", "写剪贴板（%1\$d 字）", set.length), decision = if (r.ok) "ok" else "failed") // l10n:keep test default
        }
        // l10n:keep agent tool result
        if (!get) return Out("clipboard 需要 set（写入文字）或 get:true（读取）。", error = true)
        val r = env.system().clipboardGet()
        return Out(r.message, error = !r.ok, summary = ui.get("phone_log_clipboard_read", "读剪贴板"), decision = if (r.ok) "ok" else "failed") // l10n:keep test default
    }

    // ── Screens ─────────────────────────────────────────────────────────────────────

    private fun unavailable(screen: PhoneScreen): String = when (screen) {
        PhoneScreen.BACKGROUND -> env.backgroundUnavailable()
        PhoneScreen.MAIN -> when (env.a11yState()) {
            A11yState.ENABLED_NOT_CONNECTED -> {
                env.notifyFix("a11y")
                // l10n:keep agent tool result
                "无障碍服务没连上（被系统关了或还在启动）：已通知用户到系统设置重新打开。现在只能用 apps / open / intent / notify / clipboard。"
            }
            else -> {
                env.notifyFix("a11y")
                // l10n:keep agent tool result
                "无障碍没开，看不了屏幕：已通知用户到 DSH 设置 → 手机操控 开启。现在只能用 apps / open / intent / notify / clipboard。"
            }
        }
    }

    /** The driver for [screen], or the sentence explaining why there is none. */
    private fun driverFor(screen: PhoneScreen): Either<String, PhoneDriver> {
        val d = env.driver(screen) ?: return Either.Left(unavailable(screen))
        val av = try {
            d.availability()
        } catch (e: Exception) {
            Availability(false, null)
        }
        if (!av.ok) {
            if (screen == PhoneScreen.MAIN) env.notifyFix("a11y")
            return Either.Left(av.reason ?: unavailable(screen))
        }
        return Either.Right(d)
    }

    private fun routeScreenId(id: String?): Either<String, PhoneScreen> {
        // l10n:keep agent tool result
        if (id == null) return Either.Left("缺少 screen（屏幕号，如 s7）。先调用 screen 拿到屏幕号。")
        return PhoneScreen.ofScreenId(id)?.let { Either.Right(it) }
            // l10n:keep agent tool result
            ?: Either.Left("屏幕号「$id」不对：主屏以 s 开头（如 s7），后台屏以 b 开头（如 b3）。先调用 screen 拿到屏幕号。")
    }

    private fun locked(summary: String) =
        // l10n:keep agent tool result
        Out("手机锁着屏，DSH 不在锁屏状态下操作。请用户解锁后再说一声。", error = true, summary = summary, decision = "refused:locked")

    private fun screen(a: Args): Out {
        val which = if (a.bool("background") == true) PhoneScreen.BACKGROUND else PhoneScreen.MAIN
        val summary = if (which == PhoneScreen.BACKGROUND) ui.get("phone_log_background_read", "读后台屏") else ui.get("phone_log_screen_read", "读屏") // l10n:keep test default
        val driver = when (val d = driverFor(which)) {
            is Either.Left -> return Out(d.value, error = true, summary = summary, decision = "unavailable")
            is Either.Right -> d.value
        }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        say(if (which == PhoneScreen.BACKGROUND) ui.get("phone_reading_background", "DSH 正在看后台屏") else ui.get("phone_reading_screen", "DSH 正在看屏幕")) // l10n:keep test default
        val snap = driver.observe(detail = a.bool("detail") == true, image = a.bool("image") == true)
        tasks.setApp(snap.appLabel)
        val shown = safety.filterScreen(snap)
        val refused = shown !== snap
        return Out(
            // l10n:keep agent tool result
            if (refused) "没有读取这个屏幕的内容。" else "",
            error = refused,
            screen = shown,
            app = snap.appLabel ?: snap.packageName,
            summary = "$summary ${snap.id}",
            decision = if (refused) "refused:read" else "ok",
        )
    }

    private fun collect(a: Args): Out {
        val sid = a.str("screen")
        val which = when (val r = routeScreenId(sid)) {
            is Either.Left -> return Out(r.value, error = true)
            is Either.Right -> r.value
        }
        // l10n:keep agent tool result
        val list = a.int("list") ?: return Out("collect 需要 list（列表编号）。", error = true)
        val pages = (a.int("max_pages") ?: 10).coerceIn(1, 30)
        val summary = ui.get("phone_log_collect", "收集 %1\$s#%2\$d", sid.toString(), list) // l10n:keep test default
        val driver = when (val d = driverFor(which)) {
            is Either.Left -> return Out(d.value, error = true, summary = summary, decision = "unavailable")
            is Either.Right -> d.value
        }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        val pkg = driver.foregroundPackage()
        safety.checkRead(pkg, null)?.let { return Out(it.message, error = true, app = pkg, summary = summary, decision = "refused:${it.rule}") }
        say(ui.get("phone_reading_list", "DSH 正在滚动读取列表")) // l10n:keep test default
        val r = driver.collect(sid!!, list, pages, a.str("until"))
        val screen = r.screen?.let { safety.filterScreen(it) }
        val text = buildString {
            // l10n:keep agent tool result
            append("收集到 ${r.items.size} 条（翻了 ${r.pages} 页，停下原因：${r.stopReason}）：\n")
            var size = 0
            for ((i, item) in r.items.withIndex()) {
                val line = "${i + 1}. $item\n"
                size += line.length
                if (size > MAX_COLLECT_CHARS) {
                    // l10n:keep agent tool result
                    append("…其余 ${r.items.size - i} 条超出长度上限，没有列出\n")
                    break
                }
                append(line)
            }
        }.trimEnd()
        return Out(text, screen = screen, app = screen?.appLabel ?: pkg, summary = summary, decision = "ok")
    }

    /** tap / type / scroll / tap_xy: route, resolve, gate, perform. */
    private fun act(name: String, a: Args, scope: SerialWorker.Scope): Out {
        val sid = (if (name == PhoneToolCatalog.TAP_XY) a.str("observation") else a.str("screen"))
        val which = when (val r = routeScreenId(sid)) {
            is Either.Left -> return Out(r.value, error = true)
            is Either.Right -> r.value
        }
        sid!!
        val id = a.int("id")
        val action: PhoneAction
        val kind: WriteKind
        var typed: String? = null
        var submit = false
        var point: Pair<Int, Int>? = null
        var summary: String
        when (name) {
            PhoneToolCatalog.TAP -> {
                // l10n:keep agent tool result
                id ?: return Out("tap 需要 id（元素编号）。", error = true)
                val long = a.bool("long") == true
                action = PhoneAction.Tap(sid, id, long)
                kind = if (long) WriteKind.LONG_TAP else WriteKind.TAP
                summary = if (long) ui.get("phone_log_long_tap", "长按 %1\$s#%2\$d", sid.toString(), id) else ui.get("phone_log_tap", "点 %1\$s#%2\$d", sid.toString(), id) // l10n:keep test default
            }
            PhoneToolCatalog.TYPE -> {
                // l10n:keep agent tool result
                id ?: return Out("type 需要 id（输入框编号）。", error = true)
                // l10n:keep agent tool result
                typed = a.text("text") ?: return Out("type 需要 text。", error = true)
                submit = a.bool("submit") == true
                action = PhoneAction.Type(sid, id, typed, submit)
                kind = WriteKind.TYPE
                // Masked until the input is known not to be a password or code field.
                summary = secretSummary(sid, id, submit)
            }
            PhoneToolCatalog.SCROLL -> {
                val dir = when (a.str("direction")?.lowercase()) {
                    "up" -> PhoneAction.Direction.UP
                    "down" -> PhoneAction.Direction.DOWN
                    "left" -> PhoneAction.Direction.LEFT
                    "right" -> PhoneAction.Direction.RIGHT
                    // l10n:keep agent tool result
                    else -> return Out("scroll 需要 direction：up / down / left / right。", error = true)
                }
                action = PhoneAction.Scroll(sid, id, dir)
                kind = WriteKind.SCROLL
                summary = ui.get("phone_log_scroll", "滚动 %1\$s %2\$s", sid.toString(), dir.name.lowercase()) // l10n:keep test default
            }
            else -> {
                val x = a.int("x")
                val y = a.int("y")
                // l10n:keep agent tool result
                if (x == null || y == null || x < 0 || y < 0) return Out("tap_xy 需要非负整数 x 和 y。", error = true)
                point = x to y
                action = PhoneAction.TapXY(sid, x, y)
                kind = WriteKind.TAP_XY
                summary = ui.get("phone_log_tap_xy", "点坐标 %1\$s (%2\$d,%3\$d)", sid.toString(), x, y) // l10n:keep test default
            }
        }
        val driver = when (val d = driverFor(which)) {
            is Either.Left -> return Out(d.value, error = true, summary = summary, decision = "unavailable")
            is Either.Right -> d.value
        }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        val target: TargetInfo? = if (id != null && kind != WriteKind.TAP_XY) {
            driver.target(sid, id) ?: return stale(driver, sid, summary)
        } else {
            null
        }
        val secret = kind == WriteKind.TYPE && SafetyRules.isSecretTarget(target)
        if (kind == WriteKind.TYPE && !secret) {
            summary = ui.get("phone_log_type", "输入 %1\$s#%2\$d（%3\$d 字%4\$s）", sid, id!!, typed!!.length, if (submit) ui.get("phone_log_submit", "，提交") else "") // l10n:keep test default
        }
        val pkg = target?.packageName ?: driver.foregroundPackage()
        val appLabel = target?.appLabel
        val app = appLabel ?: pkg
        val owner = tasks.activeOwner() ?: return stoppedOut(summary)
        val verdict = safety.checkWrite(kind, target, pkg, appLabel, typed, submit, point)
        val decision = when (val r = safety.resolve(verdict, scope)) {
            is SafetyGate.Result.No -> return Out(r.message, error = true, app = app, summary = summary, decision = r.decision)
            is SafetyGate.Result.Go -> r.decision
        }
        if (!tasks.isCurrent(owner) || Thread.currentThread().isInterrupted) return stoppedOut(summary)
        tasks.setApp(appLabel ?: pkg)
        say(ui.get("phone_acting", "DSH 正在操作 · %1\$s", statusLine(kind, target, if (secret) SafetyRules.MASK else typed))) // l10n:keep test default
        val res = driver.perform(action)
        return Out(
            res.message,
            error = !res.ok,
            screen = res.screen?.let { safety.filterScreen(it) },
            app = app,
            summary = summary,
            decision = if (res.ok) decision else "failed",
        )
    }

    /** A `type` in the log without its length or text: 「输入 s3#5（••••）」. */
    private fun secretSummary(sid: String, id: Int, submit: Boolean): String =
        ui.get("phone_log_type_secret", "输入 %1\$s#%2\$d（%3\$s%4\$s）", sid, id, SafetyRules.MASK, if (submit) ui.get("phone_log_submit", "，提交") else "") // l10n:keep test default

    private fun statusLine(kind: WriteKind, t: TargetInfo?, typed: String?): String {
        val label = t?.label?.takeIf { it.isNotBlank() }?.let { SafetyRules.quoteText(it, 16) } ?: t?.role?.let(ui::role) ?: ""
        return when (kind) {
            WriteKind.TAP -> ui.get("phone_status_tap", "点「%1\$s」", label) // l10n:keep test default
            WriteKind.LONG_TAP -> ui.get("phone_status_long_tap", "长按「%1\$s」", label) // l10n:keep test default
            WriteKind.TYPE -> ui.get("phone_status_type", "输入「%1\$s」", if (typed == SafetyRules.MASK) typed else SafetyRules.quoteText(typed ?: "", 16)) // l10n:keep test default
            WriteKind.SCROLL -> ui.get("phone_status_scroll", "滚动") // l10n:keep test default
            WriteKind.TAP_XY -> ui.get("phone_status_tap_xy", "点截图上的位置") // l10n:keep test default
            WriteKind.KEY -> ui.get("phone_status_key", "按键") // l10n:keep test default
        }
    }

    /** The element no longer matches its snapshot: refuse and show the screen as it is now. */
    private fun stale(driver: PhoneDriver, sid: String, summary: String): Out {
        val now = try {
            safety.filterScreen(driver.observe(detail = false, image = false))
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return Out(
            // l10n:keep agent tool result
            "屏幕 $sid 已经过期，或者这个元素变了，没有执行。按下面的新屏幕重新选。",
            error = true,
            screen = now,
            summary = summary,
            decision = "stale",
        )
    }

    private fun key(a: Args): Out {
        val key = when (a.str("key")?.lowercase()) {
            "back" -> PhoneAction.GlobalKey.BACK
            "home" -> PhoneAction.GlobalKey.HOME
            "recents" -> PhoneAction.GlobalKey.RECENTS
            "notifications" -> PhoneAction.GlobalKey.NOTIFICATIONS
            // l10n:keep agent tool result
            else -> return Out("key 只能是 back / home / recents / notifications。", error = true)
        }
        val sid = a.str("screen")
        val which = if (sid == null) {
            PhoneScreen.MAIN
        } else {
            when (val r = routeScreenId(sid)) {
                is Either.Left -> return Out(r.value, error = true)
                is Either.Right -> r.value
            }
        }
        val summary = ui.get("phone_log_key", "按键 %1\$s", key.name.lowercase()) // l10n:keep test default
        val driver = when (val d = driverFor(which)) {
            is Either.Left -> return Out(d.value, error = true, summary = summary, decision = "unavailable")
            is Either.Right -> d.value
        }
        if (env.isLocked()) return locked(summary)
        lastScreen = which
        say(ui.get("phone_status_press", "DSH 正在操作 · 按%1\$s", when (key) { // l10n:keep test default
            PhoneAction.GlobalKey.BACK -> ui.get("phone_key_back", "返回") // l10n:keep test default
            PhoneAction.GlobalKey.HOME -> ui.get("phone_key_home", "桌面") // l10n:keep test default
            PhoneAction.GlobalKey.RECENTS -> ui.get("phone_key_recents", "最近任务") // l10n:keep test default
            PhoneAction.GlobalKey.NOTIFICATIONS -> ui.get("phone_key_notifications", "通知栏") // l10n:keep test default
        }))
        val res = driver.perform(PhoneAction.Key(sid ?: "${which.prefix}0", key))
        return Out(res.message, error = !res.ok, screen = res.screen?.let { safety.filterScreen(it) }, summary = summary,
            decision = if (res.ok) "ok" else "failed")
    }

    private fun handoff(a: Args, scope: SerialWorker.Scope): Out {
        val owner = tasks.activeOwner() ?: return stoppedOut("handoff")
        // l10n:keep agent tool result
        val reason = a.str("reason") ?: return Out("handoff 需要 reason：一句话告诉用户要做什么。", error = true)
        val gate = env.gate() ?: return Out(
            // l10n:keep agent tool result
            "现在没办法把手机交给用户（DSH 不能发通知，悬浮条也没开）。直接在回复里告诉用户要做什么。",
            error = true,
            summary = ui.get("phone_log_handoff", "交还用户"), // l10n:keep test default
            decision = "refused:no-gate",
        )
        val cause = refusal?.takeIf { (who, _) -> tasks.isCurrent(who) && who.taskId == owner.taskId }?.second
        refusal = null
        val note = env.beforeHandoff(lastScreen)
        if (!tasks.isCurrent(owner)) return stoppedOut("handoff")
        val result = scope.userWait { gate.handoff(reason, HANDOFF_MS, cause) }
        if (!tasks.isCurrent(owner) || Thread.currentThread().isInterrupted) return stoppedOut("handoff")
        val prefix = note?.let { "$it\n" } ?: ""
        return when (result) {
            HandoffResult.CONTINUE -> {
                lastScreen = PhoneScreen.MAIN
                Out(prefix + "用户点了「继续」。下面是现在的屏幕；接着做之前先确认状态。", screen = mainScreenAfter(0), summary = ui.get("phone_log_handoff", "交还用户"), decision = "continue") // l10n:keep test default
            }
            HandoffResult.STOP -> {
                // l10n:keep agent tool result
                tasks.stopIfCurrent(owner, "用户在交还时选择了停止")
                Out(prefix + PhoneTasks.STOPPED_MESSAGE + "。", error = true, summary = ui.get("phone_log_handoff", "交还用户"), decision = "stopped") // l10n:keep test default
            }
            HandoffResult.TIMEOUT -> Out(
                // l10n:keep agent tool result
                prefix + "等了 10 分钟用户没有回应。停下来，告诉用户回来后可以接着做。",
                error = true,
                summary = ui.get("phone_log_handoff", "交还用户"), // l10n:keep test default
                decision = "timeout",
            )
        }
    }

    private fun stoppedOut(summary: String) = Out(PhoneTasks.STOPPED_MESSAGE + "。", error = true,
        summary = summary, decision = "stopped")

    companion object {
        /** Under DSH's 660 s MCP timeout, so the bridge always answers first. */
        const val MAX_TOTAL_MS = 640_000L
        const val PAUSE_WAIT_MS = 600_000L
        const val HANDOFF_MS = 600_000L

        /** Tools whose success means the agent got past whatever refused it before. */
        private val WRITES = setOf(PhoneToolCatalog.TAP, PhoneToolCatalog.TYPE, PhoneToolCatalog.TAP_XY, PhoneToolCatalog.OPEN)
        private val DONE = setOf("ok", "allowed", "confirmed", "confirmed-app", "confirmed-task")
        const val SETTLE_AFTER_LAUNCH_MS = 1200L
        const val MAX_APPS = 150
        const val MAX_COLLECT_CHARS = 16_000

        /** The call's own time, not counting waits for the person. */
        fun budgetMs(tool: String): Long = when (tool) {
            PhoneToolCatalog.APPS -> 15_000L
            PhoneToolCatalog.NOTIFY, PhoneToolCatalog.CLIPBOARD -> 10_000L
            PhoneToolCatalog.OPEN, PhoneToolCatalog.INTENT -> 25_000L
            PhoneToolCatalog.SCREEN -> 12_000L
            PhoneToolCatalog.COLLECT -> 120_000L
            PhoneToolCatalog.HANDOFF -> 30_000L
            else -> 30_000L
        }
    }
}
