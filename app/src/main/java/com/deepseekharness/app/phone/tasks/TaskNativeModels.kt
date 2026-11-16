package com.deepseekharness.app.phone.tasks

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** All task parameters are fixed business fields; no caller supplied SQL, URI or component. */
internal class TaskNativeFault(val code: String, override val message: String) : RuntimeException(message)

internal class TaskNativeArgs(val values: Map<String, Any?>) {
    fun text(key: String, required: Boolean = false, max: Int = 4000): String? {
        val v = values[key] ?: if (required) fail("INVALID_ARGUMENT", "需要 $key") else return null
        if (v !is String || v.length > max || '\u0000' in v || required && v.isBlank()) fail("INVALID_ARGUMENT", "$key 格式或长度无效")
        return v
    }
    fun id(key: String): Long {
        val s = values[key]?.toString() ?: fail("INVALID_ARGUMENT", "需要 $key")
        return s.toLongOrNull()?.takeIf { it > 0 } ?: fail("INVALID_ARGUMENT", "$key 必须是原始正整数 ID")
    }
    fun int(key: String, default: Int, min: Int, max: Int): Int {
        if (!values.containsKey(key)) return default
        val n = values[key]?.toString()?.toIntOrNull() ?: fail("INVALID_ARGUMENT", "$key 必须是整数")
        return n.takeIf { it in min..max } ?: fail("INVALID_ARGUMENT", "$key 超出范围")
    }
    fun bool(key: String, default: Boolean = false): Boolean = if (!values.containsKey(key)) default else values[key] as? Boolean ?: fail("INVALID_ARGUMENT", "$key 必须是布尔值")
    fun has(key: String) = values.containsKey(key)
    fun page() = TaskNativePage(int("offset", 0, 0, 10000), int("limit", 20, 1, 50))
    fun objects(key: String, max: Int): List<Map<String, Any?>> {
        val list = values[key] as? List<*> ?: fail("INVALID_ARGUMENT", "$key 必须是数组")
        if (list.isEmpty() || list.size > max) fail("INVALID_ARGUMENT", "$key 数量无效")
        return list.map { value ->
            val m = value as? Map<*, *> ?: fail("INVALID_ARGUMENT", "$key 条目必须是对象")
            if (m.keys.any { it !is String }) fail("INVALID_ARGUMENT", "$key 键无效")
            @Suppress("UNCHECKED_CAST")
            (m as Map<String, Any?>)
        }
    }
    fun reminders(): List<Int>? {
        if (!has("reminder_minutes")) return null
        val values = values["reminder_minutes"] as? List<*> ?: fail("INVALID_ARGUMENT", "reminder_minutes 必须是数组")
        if (values.size > 5) fail("INVALID_ARGUMENT", "最多5条提醒")
        return values.map { it?.toString()?.toIntOrNull()?.takeIf { n -> n in 0..40320 } ?: fail("INVALID_ARGUMENT", "提醒分钟无效") }.distinct().sorted()
    }
}

internal fun fail(code: String, text: String): Nothing = throw TaskNativeFault(code, text)
internal data class TaskNativePage(val offset: Int, val limit: Int)

internal object TaskNativeRules {
    fun like(value: String) = "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    fun number(value: String): String {
        val n = value.trim().replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
        if (!Regex("\\+?[0-9]{2,32}").matches(n)) fail("INVALID_NUMBER", "号码必须是明确普通电话号码，不接受服务码或分机指令")
        return n
    }
    fun instant(value: String): Long = try { OffsetDateTime.parse(value).toInstant().toEpochMilli() } catch (_: Exception) { fail("INVALID_ARGUMENT", "时间必须包含时区偏移") }
    fun eventTime(args: TaskNativeArgs): Triple<Long, Long, String> {
        val allDay = args.bool("all_day")
        val start = args.text("start_time", true, 80)!!
        val end = args.text("end_time", true, 80)!!
        fun time(s: String) = if (allDay && Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(s)) {
            try { LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() } catch (_: Exception) { fail("INVALID_ARGUMENT", "全天日期无效") }
        } else instant(s)
        val begin = time(start); val finish = time(end)
        if (finish <= begin) fail("INVALID_ARGUMENT", "结束时间必须晚于开始")
        if (allDay && (Math.floorMod(begin, 86400000L) != 0L || Math.floorMod(finish, 86400000L) != 0L)) fail("INVALID_ARGUMENT", "全天事件必须使用UTC零点及排他结束日期")
        val zone = if (allDay) "UTC" else args.text("timezone", false, 100) ?: OffsetDateTime.parse(start).offset.id
        try { ZoneId.of(zone) } catch (_: Exception) { fail("INVALID_ARGUMENT", "timezone 无效") }
        return Triple(begin, finish, zone)
    }
    fun fingerprint(tool: String, args: Map<String, Any?>): String {
        fun stable(v: Any?): String = when (v) {
            null -> "n"
            is Map<*, *> -> v.entries.sortedBy { it.key.toString() }.joinToString(prefix = "{", postfix = "}") { stable(it.key.toString()) + stable(it.value) }
            is List<*> -> v.joinToString(prefix = "[", postfix = "]") { stable(it) }
            else -> "${v.javaClass.simpleName}:${v.toString().length}:${v}"
        }
        return MessageDigest.getInstance("SHA-256").digest((tool + stable(args.filterKeys { it != "request_id" })).toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

internal fun taskNativeResult(action: String, status: String, text: String, effect: String = "none", verified: Boolean = false, reason: String? = null, data: Map<String, Any?> = emptyMap()): Map<String, Any?> = linkedMapOf(
    "action" to action, "backend" to "android_native", "status" to status, "effect" to effect,
    "verified" to verified, "text" to text, "reason" to reason, "data" to data,
    "retry_safe" to (effect == "none" && status !in setOf("dispatched", "unconfirmed")),
)
