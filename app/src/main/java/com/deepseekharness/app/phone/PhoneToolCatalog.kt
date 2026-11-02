package com.deepseekharness.app.phone

/**
 * The FIXED tool set the bridge serves. The model sees `mcp__phone__<name>`.
 *
 * Never make this depend on permission state: names, descriptions and schemas enter every
 * request, and any change invalidates DeepSeek's KV cache from the first changed token.
 * A call that can't run right now returns an actionable explanation instead. The JVM test pins
 * a hash of [listJson]; change it only on purpose.
 */
object PhoneToolCatalog {
    const val APPS = "apps"
    const val OPEN = "open"
    const val INTENT = "intent"
    const val NOTIFY = "notify"
    const val CLIPBOARD = "clipboard"
    const val SCREEN = "screen"
    const val COLLECT = "collect"
    const val TAP = "tap"
    const val TYPE = "type"
    const val SCROLL = "scroll"
    const val KEY = "key"
    const val TAP_XY = "tap_xy"
    const val HANDOFF = "handoff"

    class Tool(val name: String, val description: String, val properties: Map<String, Any?>, val required: List<String>) {
        fun toJson(): Map<String, Any?> = linkedMapOf(
            "name" to name,
            "description" to description,
            "inputSchema" to linkedMapOf<String, Any?>("type" to "object", "properties" to properties).apply {
                if (required.isNotEmpty()) put("required", required)
                put("additionalProperties", false)
            },
        )
    }

    val INTENT_KINDS = listOf("alarm", "timer", "calendar", "dial", "sms_draft", "share", "navigate", "settings")
    val SETTINGS_PAGES = listOf(
        "home", "wifi", "bluetooth", "mobile_network", "airplane", "display", "sound", "battery",
        "location", "date", "language", "storage", "nfc", "app_notifications",
    )
    val KEYS = listOf("back", "home", "recents", "notifications")
    val DIRECTIONS = listOf("up", "down", "left", "right")
    val NAV_MODES = listOf("drive", "transit", "walk", "ride")

    private fun str(description: String) = linkedMapOf<String, Any?>("type" to "string", "description" to description)
    private fun bool(description: String) = linkedMapOf<String, Any?>("type" to "boolean", "description" to description)
    private fun int(description: String, min: Int? = null, max: Int? = null) = linkedMapOf<String, Any?>("type" to "integer", "description" to description).apply {
        if (min != null) put("minimum", min)
        if (max != null) put("maximum", max)
    }
    private fun num(description: String) = linkedMapOf<String, Any?>("type" to "number", "description" to description)
    private fun enum(values: List<String>, description: String) =
        linkedMapOf<String, Any?>("type" to "string", "enum" to values, "description" to description)

    // l10n:keep agent tool catalog
    private val SCREEN_ID = str("屏幕号，如 s7（主屏）或 b3（后台屏），取自最近一次返回的屏幕")

    val tools: List<Tool> = listOf(
        Tool(
            APPS,
            // l10n:keep agent tool catalog
            "列出这台手机上能打开的应用（名称 · 包名）。",
            // l10n:keep agent tool catalog
            linkedMapOf("query" to str("按名称或包名筛选，可省略")),
            emptyList(),
        ),
        Tool(
            OPEN,
            // l10n:keep agent tool catalog
            "打开应用或链接（app 或 url 二选一）。background:true 在后台屏打开（需用户开通不打扰模式）；无障碍开着时返回新屏幕。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "app" to str("包名或应用名"),
                // l10n:keep agent tool catalog
                "url" to str("网址或深链"),
                // l10n:keep agent tool catalog
                "background" to bool("在后台屏打开"),
            ),
            emptyList(),
        ),
        Tool(
            INTENT,
            // l10n:keep agent tool catalog
            "用系统意图办事（最后一步用户在对方界面确认），能办就别点界面。字段按 kind：" +
                // l10n:keep agent tool catalog
                "alarm(hour,minute,days?,message?)、timer(seconds,message?)、calendar(title,begin,end?,all_day?,location?,description?)、" +
                // l10n:keep agent tool catalog
                "dial(number)、sms_draft(number?,text)、share(text,app?)、navigate(destination|lat+lng,mode?,map?)、settings(page,app?)。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "kind" to enum(INTENT_KINDS, "事务类型"),
                // l10n:keep agent tool catalog
                "hour" to int("时 0–23", 0, 23),
                // l10n:keep agent tool catalog
                "minute" to int("分 0–59", 0, 59),
                "days" to linkedMapOf(
                    "type" to "array",
                    "items" to linkedMapOf("type" to "integer", "minimum" to 1, "maximum" to 7),
                    // l10n:keep agent tool catalog
                    "description" to "重复的星期，1=周一 … 7=周日；省略只响一次",
                ),
                // l10n:keep agent tool catalog
                "seconds" to int("秒 1–86400", 1, 86400),
                // l10n:keep agent tool catalog
                "message" to str("闹钟 / 计时器标签"),
                // l10n:keep agent tool catalog
                "title" to str("日程标题"),
                // l10n:keep agent tool catalog
                "begin" to str("开始，如 2026-10-24T19:00（手机时区）；全天写 2026-10-24"),
                // l10n:keep agent tool catalog
                "end" to str("结束，省略 = 开始后 1 小时"),
                // l10n:keep agent tool catalog
                "all_day" to bool("全天"),
                // l10n:keep agent tool catalog
                "location" to str("地点"),
                // l10n:keep agent tool catalog
                "description" to str("备注"),
                // l10n:keep agent tool catalog
                "number" to str("电话号码"),
                // l10n:keep agent tool catalog
                "text" to str("文字内容"),
                // l10n:keep agent tool catalog
                "app" to str("分享目标应用（省略弹分享面板）；settings:app_notifications 用哪个应用"),
                // l10n:keep agent tool catalog
                "destination" to str("目的地名称或地址"),
                // l10n:keep agent tool catalog
                "lat" to num("纬度 GCJ-02"),
                // l10n:keep agent tool catalog
                "lng" to num("经度 GCJ-02"),
                // l10n:keep agent tool catalog
                "mode" to enum(NAV_MODES, "出行方式，默认 drive"),
                // l10n:keep agent tool catalog
                "map" to enum(listOf("amap", "baidu"), "地图，默认先高德再百度"),
                // l10n:keep agent tool catalog
                "page" to enum(SETTINGS_PAGES, "设置页；app_notifications 还需 app"),
            ),
            listOf("kind"),
        ),
        Tool(
            NOTIFY,
            // l10n:keep agent tool catalog
            "给用户发一条手机通知。",
            // l10n:keep agent tool catalog
            linkedMapOf("title" to str("标题"), "text" to str("正文")),
            listOf("title", "text"),
        ),
        Tool(
            CLIPBOARD,
            // l10n:keep agent tool catalog
            "读写剪贴板：set 写入文字；get:true 读取（系统只允许前台应用读，DSH 不在前台时读不到）。",
            // l10n:keep agent tool catalog
            linkedMapOf("set" to str("要写入的文字"), "get" to bool("读取剪贴板")),
            emptyList(),
        ),
        Tool(
            SCREEN,
            // l10n:keep agent tool catalog
            "读当前屏幕：每行一个元素，[编号] 只在本屏有效。detail:true 附坐标；image:true 附标号截图；background:true 读后台屏。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "detail" to bool("附上元素坐标"),
                // l10n:keep agent tool catalog
                "image" to bool("附带标号截图"),
                // l10n:keep agent tool catalog
                "background" to bool("读后台屏（不打扰模式）"),
            ),
            emptyList(),
        ),
        Tool(
            COLLECT,
            // l10n:keep agent tool catalog
            "只读：自动滚动列表收集全部条目（按文字去重），读长列表只用这一次。",
            linkedMapOf(
                "screen" to SCREEN_ID,
                // l10n:keep agent tool catalog
                "list" to int("列表的编号"),
                // l10n:keep agent tool catalog
                "max_pages" to int("最多翻几页，默认 10", 1, 30),
                // l10n:keep agent tool catalog
                "until" to str("出现这段文字就停"),
            ),
            listOf("screen", "list"),
        ),
        Tool(
            TAP,
            // l10n:keep agent tool catalog
            "点一个元素，返回新屏幕。敏感按钮或首次操作该 App 会先请用户确认。",
            // l10n:keep agent tool catalog
            linkedMapOf("screen" to SCREEN_ID, "id" to int("元素编号"), "long" to bool("长按")),
            listOf("screen", "id"),
        ),
        Tool(
            TYPE,
            // l10n:keep agent tool catalog
            "在输入框输入文字（替换原内容），返回回读和新屏幕。submit:true 后按回车 / 发送，要确认。密码、验证码、支付框默认拒绝，被拒绝就 handoff。",
            linkedMapOf(
                "screen" to SCREEN_ID,
                // l10n:keep agent tool catalog
                "id" to int("输入框编号"),
                // l10n:keep agent tool catalog
                "text" to str("要输入的文字"),
                // l10n:keep agent tool catalog
                "submit" to bool("输入后提交 / 发送"),
            ),
            listOf("screen", "id", "text"),
        ),
        Tool(
            SCROLL,
            // l10n:keep agent tool catalog
            "滚动，返回新屏幕。id 是要滚动的列表，省略时滚主列表。",
            linkedMapOf(
                "screen" to SCREEN_ID,
                // l10n:keep agent tool catalog
                "id" to int("列表编号，可省略"),
                // l10n:keep agent tool catalog
                "direction" to enum(DIRECTIONS, "方向"),
            ),
            listOf("screen", "direction"),
        ),
        Tool(
            KEY,
            // l10n:keep agent tool catalog
            "按系统键（back / home / recents / notifications），返回新屏幕。",
            // l10n:keep agent tool catalog
            linkedMapOf("key" to enum(KEYS, "按键"), "screen" to str("屏幕号；省略时作用于主屏")),
            listOf("key"),
        ),
        Tool(
            TAP_XY,
            // l10n:keep agent tool catalog
            "按标号截图的像素坐标点击，只在元素里找不到目标时用；每次都要用户确认。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "observation" to str("那张截图的屏幕号"),
                // l10n:keep agent tool catalog
                "x" to int("横坐标（像素）", 0),
                // l10n:keep agent tool catalog
                "y" to int("纵坐标（像素）", 0),
            ),
            listOf("observation", "x", "y"),
        ),
        Tool(
            HANDOFF,
            // l10n:keep agent tool catalog
            "把手机交给用户完成登录、验证码、支付或没把握的步骤；等用户点「继续」或「停止」，最长 10 分钟。",
            // l10n:keep agent tool catalog
            linkedMapOf("reason" to str("要用户做什么，一句话")),
            listOf("reason"),
        ),
    )

    val names: List<String> = tools.map { it.name }

    fun listJson(): List<Map<String, Any?>> = tools.map { it.toJson() }

    /** Tools that act on a screen through a [PhoneDriver] (need accessibility or the background display). */
    val SCREEN_TOOLS = setOf(SCREEN, COLLECT, TAP, TYPE, SCROLL, KEY, TAP_XY, HANDOFF)
}
