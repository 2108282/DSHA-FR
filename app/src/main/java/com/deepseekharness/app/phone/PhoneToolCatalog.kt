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
    const val CONTACTS = "contacts"
    const val MESSAGES = "messages"
    const val CALENDAR = "calendar"
    const val MEDIA = "media"

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

    val INTENT_KINDS = listOf("alarm", "timer", "calendar", "dial", "sms_draft", "share", "navigate", "settings", "call", "sms_send", "operation_status", "accounts")
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

    private fun arrayOfType(type: String, description: String) = linkedMapOf<String, Any?>(
        "type" to "array", "items" to mapOf("type" to type), "description" to description,
    )

    private val taskTools: List<Tool> = listOf(
        Tool(CONTACTS, "为电话/短信任务查询联系人及号码。search按query找人，get按contact_id读取；重名或多号码不要猜。", linkedMapOf(
            "action" to enum(listOf("search", "get"), "操作"), "query" to str("姓名或号码片段"),
            "contact_id" to str("查询返回的原始联系人ID"), "offset" to int("分页起点", 0), "limit" to int("最多50条", 1, 50),
        ), listOf("action")),
        Tool(MESSAGES, "先threads按number或地址query查会话ID，再search/read读取指定thread_id；仅任务所需范围。发送使用intent sms_send。", linkedMapOf(
            "action" to enum(listOf("threads", "search", "read"), "操作"), "thread_id" to str("search/read必须明确会话ID"),
            "number" to str("threads精确号码"),
            "query" to str("可选正文关键词"), "offset" to int("分页起点", 0), "limit" to int("最多50条", 1, 50),
        ), listOf("action")),
        Tool(CALENDAR, "通用Android日历事务：查询/读/创建/批量/改/删，事件与提醒事务写入后回查；写操作必须唯一request_id，未确认先回查不可重建。", linkedMapOf(
            "action" to enum(listOf("list_calendars", "search", "read", "create", "create_many", "update", "delete"), "操作"),
            "request_id" to str("写操作唯一ID"), "calendar_id" to str("list_calendars返回的真实ID"),
            "event_id" to str("真实事件ID，不能猜"), "query" to str("查询关键词"), "title" to str("标题"),
            "description" to str("备注"), "location" to str("地点"),
            "start_time" to str("带时区偏移ISO时间；全天YYYY-MM-DD"), "end_time" to str("结束时间，全天排他结束日期"),
            "all_day" to bool("全天"), "timezone" to str("IANA时区"), "scope" to enum(listOf("event", "series"), "重复事件删除需series"),
            "reminder_minutes" to arrayOfType("integer", "提前提醒分钟数组"),
            "events" to linkedMapOf("type" to "array", "description" to "最多30条，同一日历；每项使用title/start_time/end_time/all_day/reminder_minutes", "items" to mapOf("type" to "object")),
            "offset" to int("分页起点", 0), "limit" to int("最多50条", 1, 50),
        ), listOf("action")),
        Tool(MEDIA, "为分享等手机任务查找共享媒体/文件，返回可授权content URI；不扫描私有目录。", linkedMapOf(
            "action" to enum(listOf("search"), "操作"), "kind" to enum(listOf("images", "audio", "video", "files"), "媒体类型"),
            "query" to str("文件名关键词"), "offset" to int("分页起点", 0), "limit" to int("最多50条", 1, 50),
        ), listOf("action", "kind", "query")),
    )

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
            "直接调用原生接口，无需先screen，结果不自动读屏；仅后续确需界面操作才screen。dispatched不是业务完成；未确认写操作不可重做。call/sms_send需request_id，operation_status查询同ID结果；accounts列SIM/通话账户。字段按kind：" +
                // l10n:keep agent tool catalog
                "alarm(hour,minute,days?,message?)、timer(seconds,message?)、calendar(title,begin,end?,all_day?,location?,description?)、" +
                // l10n:keep agent tool catalog
                "dial(number)、call(number,phone_account_id?,request_id)、sms_draft(number?,text)、sms_send(number,text,subscription_id,request_id)、share(text?|path?,mime?,app?)、navigate(destination|lat+lng)、settings(page,app?)。日历优先calendar工具。",
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
                "map" to str("兼容旧参数；执行统一使用Android geo URI"),
                "path" to str("外部存储文件绝对路径或media返回的content URI"),
                "mime" to str("可选分享MIME类型"),
                "request_id" to str("原生写操作唯一ID；结果未知时查询同ID，不换ID重做"),
                "phone_account_id" to str("call的系统电话账户ID，多账户时需明确选择"),
                "subscription_id" to int("sms_send明确指定的SIM订阅ID", 0),
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
            "滚动读取指定列表。只采集此列表条目，返回complete/truncated/stop_reason；部分结果不能当全部。",
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
            "替换输入框文字（空串清空，最多2000字），返回written/submitted和新屏幕。submit按用户确认设置执行；写入成功而提交失败时不要重复输入。密码/验证码/支付框默认handoff。",
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
            "按最近标号截图像素坐标点击，仅元素无法定位时后备，遵循用户确认模式；页面或截图变化则拒绝。",
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
    ) + taskTools

    val names: List<String> = tools.map { it.name }

    fun listJson(): List<Map<String, Any?>> = tools.map { it.toJson() }

    /** Tools that act on a screen through a [PhoneDriver] (need accessibility or the background display). */
    val SCREEN_TOOLS = setOf(SCREEN, COLLECT, TAP, TYPE, SCROLL, KEY, TAP_XY, HANDOFF)
}
