package com.deepseekharness.app.phone

import android.content.pm.PackageManager

/**
 * The person-editable part of the rules: apps the agent may neither read nor
 * operate, apps where every write is confirmed, how much is asked, and the hard rules the
 * person lifted ([unlocks]). Defaults ship with the APK.
 */
data class PhonePolicies(
    val denyPackages: List<String> = SafetyRules.DEFAULT_DENY,
    val confirmEveryWritePackages: List<String> = SafetyRules.DEFAULT_CONFIRM_EVERY_WRITE,
    val confirmMode: ConfirmMode = ConfirmMode.ASK,
    val unlocks: PhoneUnlocks = PhoneUnlocks(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "denyPackages" to denyPackages,
        "confirmEveryWritePackages" to confirmEveryWritePackages,
        "confirmMode" to confirmMode.wire,
        "unlocks" to unlocks.toMap(),
    )

    companion object {
        /** Reads `setPolicies` arguments / the stored JSON; a missing entry keeps [base]'s value. */
        fun fromMap(map: Map<*, *>?, base: PhonePolicies = PhonePolicies()): PhonePolicies {
            fun list(key: String, keep: List<String>): List<String> {
                val raw = map?.get(key) as? List<*> ?: return keep
                return raw.mapNotNull { (it as? String)?.trim()?.takeIf { s -> PACKAGE.matches(s) } }.distinct()
            }
            return PhonePolicies(
                list("denyPackages", base.denyPackages),
                list("confirmEveryWritePackages", base.confirmEveryWritePackages),
                ConfirmMode.fromWire(map?.get("confirmMode") as? String) ?: base.confirmMode,
                PhoneUnlocks.fromMap(map?.get("unlocks") as? Map<*, *>, base.unlocks),
            )
        }

        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}

/**
 * 解除限制: hard rules the person lifted one by one, at their own risk, behind a confirmation
 * page in the app. Each is off by default, and anything missing or unreadable reads as off: a
 * value that isn't a boolean keeps [base]'s, and stored policies are read on top of the
 * defaults. Changed only by a `setPolicies` that names it (the app sends nothing else with it).
 */
data class PhoneUnlocks(
    /** System settings, permission controllers, installers, OEM security centres, SystemUI. */
    val systemPages: Boolean = false,
    /** Password, verification-code and payment-code inputs. Typed values never reach a log. */
    val secretFields: Boolean = false,
    /** DSHA's own screens (not its overlay, which the screen reader never sees). */
    val ownApp: Boolean = false,
) {
    val any: Boolean get() = systemPages || secretFields || ownApp

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "systemPages" to systemPages,
        "secretFields" to secretFields,
        "ownApp" to ownApp,
    )

    companion object {
        fun fromMap(map: Map<*, *>?, base: PhoneUnlocks = PhoneUnlocks()): PhoneUnlocks {
            fun flag(key: String, keep: Boolean) = map?.get(key) as? Boolean ?: keep
            return PhoneUnlocks(
                flag("systemPages", base.systemPages),
                flag("secretFields", base.secretFields),
                flag("ownApp", base.ownApp),
            )
        }
    }
}

/**
 * How much the person is asked. Whatever the mode, the hard refusals still apply (own app, deny
 * list, system pages, password / code fields) unless the person lifted them ([PhoneUnlocks]):
 * those are where one wrong tap costs money or an account, and what prompt injection aims at.
 */
enum class ConfirmMode(val wire: String) {
    /** 每次都询问 (default): first step in each app, sensitive words, every-write apps, tap_xy. */
    ASK("ask"),

    /** 本任务免确认: the first question of a task offers to waive the rest of that task. */
    TASK("task"),

    /** 始终免确认: nothing is asked. */
    ALWAYS("always");

    companion object {
        fun fromWire(value: String?): ConfirmMode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Fixed safety rules that are not the overlay's own UI. Native and not configurable: the defence
 * against prompt injection is here, not in AGENTS.md (a web page saying "transfer money" can fool
 * the model, not this code). Public so the screen reader can mark `⚠` with the same word list.
 */
object SafetyRules {
    const val OWN_PACKAGE = "com.dsha.fr"

    /**
     * The word that makes tapping [label] sensitive, or null. Delegates to [SensitiveWords] (shared
     * with the screen reader's `⚠` marks) so what is marked is exactly what gets confirmed.
     */
    fun sensitiveWord(label: String?, viewId: String? = null): String? = SensitiveWords.confirmHit(label, viewId)

    /** An input the agent must never fill unless the person allowed it ([PhoneUnlocks.secretFields]). */
    fun isSecret(label: String?, viewId: String? = null): Boolean = SensitiveWords.secretHit(label, null, viewId) != null

    /** A password / code input: refused while locked, its typed value masked everywhere once allowed. */
    fun isSecretTarget(t: TargetInfo?): Boolean =
        t != null && (t.isPassword || t.isSecretField || (t.isEditable && isSecret(t.label, t.viewId)))

    /** What a typed secret shows as in the log, the status line and a confirmation. */
    const val MASK = "••••"

    /**
     * System pages where the agent may read but never act: settings (accessibility, device admin,
     * developer options live there), permission controllers, package installers, OEM security
     * centres, and SystemUI (quick settings, the notification shade with our own confirm buttons,
     * the lock screen). The contract gives us packages, not pages, so the whole app counts.
     */
    val SYSTEM_PACKAGES = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller", "com.miui.packageinstaller",
        "com.android.settings.intelligence",
        "com.miui.securitycenter", "com.miui.securityadd", "com.miui.securitycore",
        "com.coloros.safecenter", "com.oplus.safecenter", "com.coloros.securitypermission", "com.oplus.securitypermission",
        "com.vivo.permissionmanager", "com.iqoo.secure",
        "com.huawei.systemmanager", "com.hihonor.systemmanager", "com.huawei.security", "com.hihonor.security",
        "com.samsung.android.lool", "com.samsung.android.sm", "com.samsung.accessibility",
        "com.google.android.marvin.talkback",
        "com.android.keychain", "com.android.vpndialogs", "com.android.certinstaller",
    )

    fun isSystemPackage(pkg: String?): Boolean = pkg != null && pkg in SYSTEM_PACKAGES

    /** Banks, brokers, payment: neither read nor write by default. Wrong guesses just never match. */
    val DEFAULT_DENY = listOf(
        // payment
        "com.eg.android.AlipayGphone", "com.unionpay", "com.chinatelecom.bestpayclient", "com.jd.jrapp",
        "cn.gov.pbc.dcep", "com.paypal.android.p2pmobile", "com.mybank.android.phone", "com.webank.wemoney",
        // banks
        "com.icbc", "com.chinamworld.main", "com.android.bankabc", "com.chinamworld.bocmbci", "com.bankcomm.Bankcomm",
        "cmb.pb", "com.cmbchina.ccd.pluto.cmbActivity", "com.yitong.mbank.psbc", "cn.com.spdb.mobilebank.per",
        "cn.com.cmbc.newmbank", "com.ecitic.bank.mobile", "com.cebbank.mobile.cemb", "com.pingan.paces.ccms",
        "com.cgbchina.xpt", "com.bankofbeijing.mobilebanking",
        // brokers
        "com.hexin.plat.android", "com.eastmoney.android.berlin", "com.lphtsccft", "com.xueqiu.android",
        "cn.futu.trader", "com.tigerbrokers.stock",
    )

    /** WeChat: readable, every write confirmed. */
    val DEFAULT_CONFIRM_EVERY_WRITE = listOf("com.tencent.mm")

    /**
     * 未安装知名应用的中文名称回退表，用于应用名单列表展示与安全阻断时的可读提示。
     */
    val KNOWN_APP_LABELS: Map<String, String> = mapOf(
        // 支付 / 金融
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.unionpay" to "云闪付",
        "com.chinatelecom.bestpayclient" to "翼支付",
        "com.jd.jrapp" to "京东金融",
        "cn.gov.pbc.dcep" to "数字人民币",
        "com.paypal.android.p2pmobile" to "PayPal",
        "com.mybank.android.phone" to "网商银行",
        "com.webank.wemoney" to "微众银行",
        // 银行
        "com.icbc" to "中国工商银行",
        "com.chinamworld.main" to "中国建设银行",
        "com.android.bankabc" to "中国农业银行",
        "com.chinamworld.bocmbci" to "中国银行",
        "com.bankcomm.Bankcomm" to "交通银行",
        "cmb.pb" to "招商银行",
        "com.cmbchina.ccd.pluto.cmbActivity" to "掌上生活 (招行信用卡)",
        "com.yitong.mbank.psbc" to "中国邮政储蓄银行",
        "cn.com.spdb.mobilebank.per" to "浦发银行",
        "cn.com.cmbc.newmbank" to "中国民生银行",
        "com.ecitic.bank.mobile" to "中信银行",
        "com.cebbank.mobile.cemb" to "中国光大银行",
        "com.pingan.paces.ccms" to "平安口袋银行",
        "com.cgbchina.xpt" to "广发银行",
        "com.bankofbeijing.mobilebanking" to "北京银行",
        // 券商
        "com.hexin.plat.android" to "同花顺",
        "com.eastmoney.android.berlin" to "东方财富",
        "com.lphtsccft" to "涨乐财富通",
        "com.xueqiu.android" to "雪球",
        "cn.futu.trader" to "富途牛牛",
        "com.tigerbrokers.stock" to "老虎国际",
        // 常用应用
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ",
        "com.taobao.taobao" to "淘宝",
        "com.jingdong.app.mall" to "京东",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.sankuai.meituan" to "美团",
        "com.autonavi.minimap" to "高德地图",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.xingin.xhs" to "小红书",
        "com.MobileTicket" to "铁路12306",
    )

    fun labelFor(pm: PackageManager?, packageName: String): String {
        if (pm != null) {
            try {
                val info = pm.getApplicationInfo(packageName, 0)
                val label = pm.getApplicationLabel(info).toString().trim()
                if (label.isNotEmpty() && label != packageName) return label
            } catch (_: Exception) {}
        }
        return KNOWN_APP_LABELS[packageName] ?: packageName
    }

    fun quoteText(text: String, max: Int = 60): String {
        val t = text.replace('\n', ' ').trim()
        return if (t.length <= max) t else t.take(max) + "…"
    }
}

/** The kinds of writes the gate distinguishes. */
enum class WriteKind { TAP, LONG_TAP, TYPE, TAP_XY, SCROLL, KEY }

/**
 * Applies the safety rules to one action and asks the person when a rule says so, through [gate]
 * (`PhoneRegistry.gate()`: the overlay, else the heads-up notification). Plain Kotlin; the
 * calling worker passes its [SerialWorker.Scope] so waiting for the person isn't charged
 * to the action's time budget.
 */
class SafetyGate(
    private val policies: () -> PhonePolicies,
    private val tasks: PhoneTasks,
    private val gate: () -> UserGate?,
    private val ownPackage: String = SafetyRules.OWN_PACKAGE,
    private val uiText: PhoneUiText = PhoneUiText.DEFAULT,
) {
    sealed interface Verdict {
        object Allow : Verdict
        class Refuse(val message: String, val rule: String) : Verdict
        class Confirm(val request: ConfirmRequest, val packageName: String?, val reason: String) : Verdict
    }

    /** The outcome of asking (or of not needing to ask). */
    sealed interface Result {
        /** [decision] null when no question was needed. */
        class Go(val decision: String) : Result
        class No(val message: String, val decision: String) : Result
    }

    // l10n:keep agent safety result
    fun labelOf(pkg: String?, appLabel: String?) = appLabel ?: pkg ?: "当前应用"

    /** Reads (screen, collect, the screen attached to results): own app and deny list refuse. */
    fun checkRead(pkg: String?, appLabel: String?): Verdict.Refuse? {
        val p = policies()
        if (pkg == ownPackage && !p.unlocks.ownApp) return Verdict.Refuse(OWN_APP, "own-app")
        if (pkg != null && pkg in p.denyPackages) return Verdict.Refuse(denied(labelOf(pkg, appLabel)), "deny-list")
        return null
    }

    /** Opening an app (main or background screen). */
    fun checkOpen(pkg: String, appLabel: String?): Verdict.Refuse? {
        val p = policies()
        // l10n:keep agent safety result
        if (pkg == ownPackage && !p.unlocks.ownApp) return Verdict.Refuse("DSH 自己不需要用 open 打开。", "own-app")
        if (pkg in p.denyPackages) return Verdict.Refuse(denied(labelOf(pkg, appLabel)), "deny-list")
        return null
    }

    /**
     * One write on a screen. [target] is the resolved element (null for tap_xy / key / scroll
     * without an element); [pkg] is the app in front of that screen.
     */
    fun checkWrite(
        kind: WriteKind,
        target: TargetInfo?,
        pkg: String?,
        appLabel: String?,
        typed: String? = null,
        submit: Boolean = false,
        point: Pair<Int, Int>? = null,
    ): Verdict {
        val app = labelOf(pkg, appLabel)
        if (kind == WriteKind.KEY) return Verdict.Allow // back / home / recents / shade: always a way out
        val p = policies()
        val lifted = p.unlocks
        if (pkg == ownPackage && !lifted.ownApp) return Verdict.Refuse(OWN_APP, "own-app")
        if (pkg != null && pkg in p.denyPackages) return Verdict.Refuse(denied(app), "deny-list")
        if (kind == WriteKind.SCROLL) return Verdict.Allow
        if (SafetyRules.isSystemPackage(pkg) && !lifted.systemPages) {
            // l10n:keep agent safety result
            return Verdict.Refuse("系统设置 / 权限 / 安装页（「$app」）只看不动。请 handoff 让用户自己完成。", "system-page")
        }
        val secret = SafetyRules.isSecretTarget(target)
        if (secret && !lifted.secretFields) {
            // l10n:keep agent safety result
            return Verdict.Refuse("密码 / 验证码 / 支付框不代填也不代点。请 handoff 让用户输入。", "secret-field")
        }
        // Everything above is a hard rule (or one the person lifted); everything below only asks.
        if (waived(p)) return Verdict.Allow
        val everyWrite = pkg != null && pkg in p.confirmEveryWritePackages
        val firstInApp = !everyWrite && (pkg == null || !tasks.isApproved(pkg))
        val word = if (kind == WriteKind.TAP || kind == WriteKind.LONG_TAP) SafetyRules.sensitiveWord(target?.label, target?.viewId) else null
        val actionApp = appLabel ?: pkg ?: uiText.get("phone_current_app", "当前应用") // l10n:keep test default
        val action = describe(kind, target, actionApp, if (secret) SafetyRules.MASK else typed, submit, point)
        val reason = when {
            kind == WriteKind.TAP_XY -> "tap_xy"
            kind == WriteKind.TYPE && submit -> "submit"
            word != null -> "sensitive:$word"
            everyWrite -> "every-write"
            firstInApp -> "first-in-app"
            else -> return Verdict.Allow
        }
        val sentence = if (reason == "first-in-app") uiText.get("phone_confirm_first_app", "允许 DSH 在本任务中操作「%1\$s」？第一步：%2\$s", actionApp, action) else action // l10n:keep test default
        return Verdict.Confirm(
            ConfirmRequest(sentence = sentence, appLabel = appLabel ?: pkg, packageName = pkg, firstInApp = firstInApp, offerTaskWaiver = p.confirmMode == ConfirmMode.TASK),
            pkg,
            reason,
        )
    }

    /** Nothing more to ask: 始终免确认, or 本任务免确认 already granted for this task. */
    fun waived(p: PhonePolicies = policies()): Boolean =
        p.confirmMode == ConfirmMode.ALWAYS || (p.confirmMode == ConfirmMode.TASK && tasks.isWaived)

    /** A plain confirmation the core needs outside [checkWrite] (moving an app to the background screen). */
    fun confirmation(sentence: String, pkg: String?, appLabel: String?, reason: String): Verdict {
        val p = policies()
        if (waived(p)) return Verdict.Allow
        val request = ConfirmRequest(sentence = sentence, appLabel = appLabel ?: pkg, packageName = pkg, firstInApp = false, offerTaskWaiver = p.confirmMode == ConfirmMode.TASK)
        return Verdict.Confirm(request, pkg, reason)
    }

    /** Resolves a verdict, asking the person if needed. InterruptedException means stop. */
    fun resolve(verdict: Verdict, scope: SerialWorker.Scope): Result = when (verdict) {
        Verdict.Allow -> Result.Go("allowed")
        is Verdict.Refuse -> Result.No(verdict.message, "refused:${verdict.rule}")
        is Verdict.Confirm -> ask(verdict, scope)
    }

    private fun ask(v: Verdict.Confirm, scope: SerialWorker.Scope): Result {
        val g = gate() ?: return Result.No(
            // l10n:keep agent safety result
            "这一步需要用户确认，但现在没办法问用户：请用户允许 DSH 发通知，或开启 DSH 的无障碍（悬浮条确认）。",
            "refused:no-gate",
        )
        val owner = tasks.activeOwner() ?: return stopped()
        val decision = scope.userWait { g.confirm(v.request) }
        // A real tap is not authority over a task that replaced the one being asked about.
        if (!tasks.isCurrent(owner)) return stopped()
        return when (decision) {
            Decision.ALLOW_ONCE -> if (tasks.confirmIfCurrent(owner)) Result.Go("confirmed") else stopped()
            Decision.ALLOW_APP_FOR_TASK -> {
                val pkg = v.packageName
                val approved = pkg?.takeUnless { it in policies().confirmEveryWritePackages }
                if (tasks.confirmIfCurrent(owner, pkg = approved)) Result.Go("confirmed-app") else stopped()
            }
            Decision.ALLOW_ALL_FOR_TASK -> {
                if (tasks.confirmIfCurrent(owner, waiveTask = true)) Result.Go("confirmed-task") else stopped()
            }
            // l10n:keep agent safety result
            Decision.DENY -> Result.No("用户拒绝了这一步（或 30 秒内没有选择）。不要重试同一个动作；换个做法，或问用户想怎么做。", "denied")
            Decision.STOP -> {
                // l10n:keep agent safety result
                tasks.stopIfCurrent(owner, "用户在确认时选择了停止")
                Result.No(PhoneTasks.STOPPED_MESSAGE + "。", "stopped")
            }
            Decision.UNAVAILABLE -> Result.No(
                // l10n:keep agent safety result
                "这一步需要用户确认，但确认界面现在不可用。请用户打开 DSHA 检查通知与手机操控设置；不要自动重试操作。",
                "refused:no-gate",
            )
        }
    }

    private fun stopped() = Result.No(PhoneTasks.STOPPED_MESSAGE + "。", "stopped")

    /**
     * The screen the model gets back: replaced by a one-line refusal for DSH itself, deny-listed
     * apps and secure windows. Images go with the text they belong to; a screen with a bank card or
     * ID number (masked in the text) keeps its text but loses the image.
     */
    fun filterScreen(s: ScreenSnapshot): ScreenSnapshot {
        val p = policies()
        val refusal = when {
            s.packageName == ownPackage && !p.unlocks.ownApp -> OWN_APP
            s.packageName != null && s.packageName in p.denyPackages -> denied(labelOf(s.packageName, s.appLabel))
            // l10n:keep agent safety result
            s.secure -> "安全窗口（禁止截屏 / 读取）。要操作请 handoff。"
            // l10n:keep agent safety result
            s.image != null && ("«银行卡" in s.text || "«身份证" in s.text) ->
                // l10n:keep agent safety result
                return s.copy(text = s.text + "\n提示：本屏有银行卡号或身份证号，这次没有附截图。", image = null, imageMime = null)
            else -> return s
        }
        val app = s.appLabel?.let { " · $it" } ?: ""
        // l10n:keep agent safety result
        return s.copy(text = "屏幕 ${s.id}$app\n$refusal", image = null, imageMime = null)
    }

    private fun describe(kind: WriteKind, t: TargetInfo?, app: String, typed: String?, submit: Boolean, point: Pair<Int, Int>?): String {
        val label = t?.label?.takeIf { it.isNotBlank() }?.let { SafetyRules.quoteText(it, 30) } ?: t?.role?.let(uiText::role) ?: uiText.get("phone_confirm_element", "元素") // l10n:keep test default
        return when (kind) {
            WriteKind.TAP -> uiText.get("phone_confirm_tap", "在「%1\$s」点「%2\$s」", app, label) // l10n:keep test default
            WriteKind.LONG_TAP -> uiText.get("phone_confirm_long_tap", "在「%1\$s」长按「%2\$s」", app, label) // l10n:keep test default
            WriteKind.TYPE -> uiText.get("phone_confirm_type", "在「%1\$s」的「%2\$s」输入：\"%3\$s\"", app, label, if (typed == SafetyRules.MASK) typed else SafetyRules.quoteText(typed ?: "")) + if (submit) uiText.get("phone_confirm_submit", "，然后发送 / 提交") else "" // l10n:keep test default
            WriteKind.TAP_XY -> uiText.get("phone_confirm_tap_xy", "在「%1\$s」点截图上的位置 (%2\$s, %3\$s)", app, point?.first.toString(), point?.second.toString()) // l10n:keep test default
            WriteKind.SCROLL -> uiText.get("phone_confirm_scroll", "在「%1\$s」滚动", app) // l10n:keep test default
            WriteKind.KEY -> uiText.get("phone_confirm_key", "按系统键") // l10n:keep test default
        }
    }

    companion object {
        // l10n:keep agent safety result
        const val OWN_APP = "这是 DSH 自己的界面，不读取也不操作。"
        fun denied(app: String) =
            // l10n:keep agent safety result
            "「$app」默认不碰（银行、券商、支付类）。要用就 handoff 给用户，或让用户在 设置 → 手机操控 → 应用名单 里解除。"
    }
}
