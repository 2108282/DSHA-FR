package com.deepseekharness.app.phone.tasks

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract as C

/** Fixed CalendarContract transactions; event IDs always refer to original Provider records. */
internal class TaskNativeCalendar(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val eventColumns = arrayOf("_id", "calendar_id", "title", "description", "eventLocation", "dtstart", "dtend", "eventTimezone", "allDay", "rrule", "rdate", "original_id", "deleted")
    private val writes = setOf("create", "create_many", "update", "delete")

    fun prepare(a: TaskNativeArgs): () -> Map<String, Any?> {
        val action = a.text("action", true, 30)!!
        if (action !in writes + setOf("list_calendars", "search", "read")) fail("INVALID_ARGUMENT", "未知calendar action")
        permission(Manifest.permission.READ_CALENDAR)
        if (action in writes) permission(Manifest.permission.WRITE_CALENDAR)
        return when (action) {
            "list_calendars" -> ({ observed(action, mapOf("items" to calendars())) })
            "read" -> {
                val id = a.id("event_id")
                ({ val event = read(id) ?: fail("EVENT_NOT_FOUND", "没有该原始日程ID"); observed(action, mapOf("event" to event)) })
            }
            "search" -> prepareSearch(a)
            "create", "create_many" -> prepareCreate(action, a)
            "update" -> prepareUpdate(a)
            else -> prepareDelete(a)
        }
    }
    private fun permission(p: String) { if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) fail("PERMISSION_REQUIRED", "缺少权限 $p；未请求授权") }
    private fun calendars(): List<Map<String, Any?>> = rows(C.Calendars.CONTENT_URI, arrayOf("_id", "calendar_displayName", "calendar_access_level", "visible", "maxReminders", "allowedReminders"), "calendar_access_level>=?", arrayOf(C.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), "_id ASC", TaskNativePage(0, 100)).first.map { it + ("calendar_id" to it["_id"].toString()) }
    private fun chooseCalendar(a: TaskNativeArgs): Map<String, Any?> {
        val available = calendars()
        if (a.has("calendar_id")) return available.firstOrNull { it["_id"].toString() == a.id("calendar_id").toString() } ?: fail("CALENDAR_NOT_WRITABLE", "指定日历不存在或不可写")
        val choices = available.filter { it["visible"].toString() == "1" }.ifEmpty { available }
        if (choices.size != 1) fail("CALENDAR_SELECTION_REQUIRED", "请先list_calendars并明确calendar_id，不能猜测")
        return choices.single()
    }
    private fun values(a: TaskNativeArgs, creating: Boolean, old: Map<String, Any?>? = null): ContentValues {
        if (a.has("rrule") || a.has("rdate") || a.has("duration")) fail("UNSUPPORTED_RECURRENCE_WRITE", "当前不创建或编辑重复规则")
        val out = ContentValues()
        a.text("title", creating, 200)?.let { out.put("title", it) }
        a.text("description", false, 8000)?.let { out.put("description", it) }
        a.text("location", false, 500)?.let { out.put("eventLocation", it) }
        if (creating || a.has("start_time") || a.has("end_time") || a.has("all_day") || a.has("timezone")) {
            if (!a.has("start_time") || !a.has("end_time")) fail("INVALID_ARGUMENT", "修改时间/全天属性必须同时提供start_time和end_time")
            val args = if (!creating && !a.has("all_day")) TaskNativeArgs(a.values + ("all_day" to (old?.get("allDay").toString() == "1"))) else a
            val (start, end, zone) = TaskNativeRules.eventTime(args)
            out.put("dtstart", start); out.put("dtend", end); out.put("eventTimezone", zone); out.put("allDay", if (args.bool("all_day")) 1 else 0)
        }
        return out
    }
    private fun checkReminders(calendar: Map<String, Any?>, reminders: List<Int>) {
        val max = calendar["maxReminders"]?.toString()?.toIntOrNull()
        if (max != null && max >= 0 && reminders.size > max) fail("REMINDERS_UNSUPPORTED", "提醒数量超过该日历支持上限")
        val allowed = calendar["allowedReminders"]?.toString()?.split(',')?.mapNotNull { it.toIntOrNull() }
        if (reminders.isNotEmpty() && allowed != null && C.Reminders.METHOD_ALERT !in allowed) fail("REMINDERS_UNSUPPORTED", "该日历不支持本地提醒")
    }
    private fun insertReminders(ops: ArrayList<ContentProviderOperation>, id: Long?, back: Int?, reminders: List<Int>) {
        for (minute in reminders) {
            val builder = ContentProviderOperation.newInsert(C.Reminders.CONTENT_URI)
                .withValue(C.Reminders.MINUTES, minute).withValue(C.Reminders.METHOD, C.Reminders.METHOD_ALERT)
            if (back != null) builder.withValueBackReference(C.Reminders.EVENT_ID, back) else builder.withValue(C.Reminders.EVENT_ID, id)
            ops.add(builder.build())
        }
    }
    private fun prepareCreate(action: String, a: TaskNativeArgs): () -> Map<String, Any?> {
        val entries = if (action == "create_many") a.objects("events", 30) else listOf(a.values)
        val prepared = entries.map { raw ->
            val entry = TaskNativeArgs(if (a.has("calendar_id")) raw + ("calendar_id" to a.id("calendar_id")) else raw)
            val calendar = chooseCalendar(entry)
            val v = values(entry, true).apply { put("calendar_id", calendar["_id"].toString().toLong()) }
            val rem = entry.reminders() ?: emptyList()
            checkReminders(calendar, rem)
            Triple(v, rem, calendar["_id"].toString())
        }
        if (prepared.map { it.third }.distinct().size != 1) fail("CALENDAR_SELECTION_REQUIRED", "批量创建必须属于同一个日历")
        val ops = ArrayList<ContentProviderOperation>(); val eventIndexes = ArrayList<Int>()
        prepared.forEach { (v, rem, _) ->
            val idx = ops.size; eventIndexes += idx
            ops += ContentProviderOperation.newInsert(C.Events.CONTENT_URI).withValues(v).build()
            insertReminders(ops, null, idx, rem)
        }
        return {
            val result = resolver.applyBatch(C.AUTHORITY, ops)
            val ids = eventIndexes.map { index -> result.getOrNull(index)?.uri?.let { ContentUris.parseId(it) } ?: fail("WRITE_RESULT_UNKNOWN", "日历未返回原始事件ID") }
            val after = ids.map { read(it) }
            val verified = after.indices.all { i -> after[i]?.let { matches(it, prepared[i].first, prepared[i].second) } == true }
            taskNativeResult("calendar.$action", if (verified) "completed" else "unconfirmed", if (verified) "日程与提醒已事务写入并读回核对" else "日历写入结果未完整验证，请先查询不要重复创建", "applied", verified,
                data = mapOf("event_ids" to ids.map(Long::toString), "events" to after, "verification_scope" to "event_fields_and_alert_reminders_not_sync_or_alarm_firing"))
        }
    }
    private fun prepareUpdate(a: TaskNativeArgs): () -> Map<String, Any?> {
        val id = a.id("event_id"); val before = read(id) ?: fail("EVENT_NOT_FOUND", "没有该日程")
        protectRecurring(before)
        val calendar = chooseCalendar(TaskNativeArgs(mapOf("calendar_id" to before["calendar_id"])))
        val v = values(a, false, before); val rem = a.reminders()
        if (v.size() == 0 && rem == null) fail("INVALID_ARGUMENT", "没有需要修改的字段")
        if (rem != null) checkReminders(calendar, rem)
        val ops = arrayListOf(assertEvent(id, before))
        if (v.size() > 0) ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(C.Events.CONTENT_URI, id)).withValues(v).withExpectedCount(1).build()
        if (rem != null) {
            ops += ContentProviderOperation.newDelete(C.Reminders.CONTENT_URI).withSelection("event_id=?", arrayOf(id.toString())).build()
            insertReminders(ops, id, null, rem)
        }
        return {
            resolver.applyBatch(C.AUTHORITY, ops)
            val after = read(id)
            val verified = after?.let { event ->
                val fieldsMatch = v.valueSet().all { (key, value) -> event[key]?.toString() == value?.toString() }
                fieldsMatch && if (rem != null) matches(event, ContentValues(), rem) else event["reminders"] == before["reminders"]
            } == true
            taskNativeResult("calendar.update", if (verified) "completed" else "unconfirmed", if (verified) "日程修改已读回核对" else "日程可能已修改，结果未确认，请先查询", "applied", verified, data = mapOf("event_id" to id.toString(), "event" to after, "verification_scope" to "requested_fields_and_reminders"))
        }
    }
    private fun prepareDelete(a: TaskNativeArgs): () -> Map<String, Any?> {
        val id = a.id("event_id"); val before = read(id) ?: fail("EVENT_NOT_FOUND", "没有该日程")
        chooseCalendar(TaskNativeArgs(mapOf("calendar_id" to before["calendar_id"])))
        if (recurring(before) && a.text("scope", false, 20) != "series") fail("RECURRING_SCOPE_REQUIRED", "删除重复日程必须明确scope=series；不支持单次实例删除")
        if (before["original_id"] != null) fail("RECURRING_EXCEPTION_UNSUPPORTED", "请使用重复事件原始系列ID")
        val ops = arrayListOf(assertEvent(id, before), ContentProviderOperation.newDelete(ContentUris.withAppendedId(C.Events.CONTENT_URI, id)).withExpectedCount(1).build())
        return {
            resolver.applyBatch(C.AUTHORITY, ops)
            val verified = read(id) == null
            taskNativeResult("calendar.delete", if (verified) "completed" else "unconfirmed", if (verified) "日程删除已读回核对" else "删除状态未确认，请先查询", "applied", verified, data = mapOf("event_id" to id.toString()))
        }
    }
    private fun assertEvent(id: Long, before: Map<String, Any?>): ContentProviderOperation {
        val fields = ContentValues()
        for (key in eventColumns.filter { it != "_id" }) {
            val value = before[key]
            if (value == null) fields.putNull(key) else fields.put(key, value.toString())
        }
        return ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(C.Events.CONTENT_URI, id)).withValues(fields).withExpectedCount(1).build()
    }
    private fun recurring(row: Map<String, Any?>) = !row["rrule"]?.toString().isNullOrBlank() || !row["rdate"]?.toString().isNullOrBlank()
    private fun protectRecurring(row: Map<String, Any?>) { if (recurring(row) || row["original_id"] != null) fail("RECURRING_UPDATE_UNSUPPORTED", "不修改重复事件或其例外；请使用原始系列并明确所需行为") }
    private fun read(id: Long): Map<String, Any?>? {
        val event = rows(C.Events.CONTENT_URI, eventColumns, "_id=? AND deleted=0", arrayOf(id.toString()), "_id ASC", TaskNativePage(0, 1)).first.firstOrNull() ?: return null
        val rem = rows(C.Reminders.CONTENT_URI, arrayOf("minutes", "method"), "event_id=?", arrayOf(id.toString()), "minutes ASC, method ASC", TaskNativePage(0, 100)).first
        return event + mapOf("event_id" to id.toString(), "reminders" to rem)
    }
    private fun matches(event: Map<String, Any?>, expected: ContentValues, reminders: List<Int>): Boolean {
        if (expected.valueSet().any { (key, value) -> event[key]?.toString() != value?.toString() }) return false
        val actual = event["reminders"] as? List<*> ?: return false
        return actual.all { (it as? Map<*, *>)?.get("method").toString() == C.Reminders.METHOD_ALERT.toString() } && actual.map { (it as Map<*, *>)["minutes"].toString().toInt() }.sorted() == reminders.sorted()
    }
    private fun prepareSearch(a: TaskNativeArgs): () -> Map<String, Any?> {
        val q = a.text("query", false, 200); val page = a.page()
        val args = ArrayList<String>(); val filters = arrayListOf("deleted=0")
        if (q != null) { filters += "(title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\')"; args += TaskNativeRules.like(q); args += TaskNativeRules.like(q) }
        if (a.has("calendar_id")) { filters += "calendar_id=?"; args += a.id("calendar_id").toString() }
        val timed = a.has("start_time") || a.has("end_time")
        val uri: Uri; val columns: Array<String>; val sort: String
        if (timed) {
            val start = TaskNativeRules.instant(a.text("start_time", true, 80)!!); val end = TaskNativeRules.instant(a.text("end_time", true, 80)!!)
            if (end <= start || end - start > 366L * 86400000L) fail("INVALID_ARGUMENT", "查询窗口必须为正且不超过366天")
            uri = C.Instances.CONTENT_URI.buildUpon().appendPath(start.toString()).appendPath(end.toString()).build()
            columns = arrayOf("event_id", "begin", "end", "title", "calendar_id", "allDay", "eventTimezone"); sort = "begin ASC, event_id ASC"
        } else { uri = C.Events.CONTENT_URI; columns = eventColumns; sort = "dtstart ASC, _id ASC" }
        return {
            val (items, more) = rows(uri, columns, filters.joinToString(" AND "), args.toTypedArray(), sort, page)
            observed("search", mapOf("items" to items, "has_more" to more, "next_offset" to if (more) page.offset + items.size else null, "result_scope" to if (timed) "event_instances" else "event_definitions"))
        }
    }
    private fun observed(action: String, data: Map<String, Any?>) = taskNativeResult("calendar.$action", "observed", "已读取日历Provider结果", verified = true, data = data)
    private fun rows(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, sort: String, page: TaskNativePage): Pair<List<Map<String, Any?>>, Boolean> {
        val result = ArrayList<Map<String, Any?>>()
        val c = resolver.query(uri, projection, selection, args, sort) ?: fail("PROVIDER_UNAVAILABLE", "日历未返回读取结果")
        c.use {
            if (!it.moveToPosition(page.offset)) return result to false
            do {
                if (result.size == page.limit) return result to true
                result += (0 until it.columnCount).associate { i -> it.getColumnName(i) to when (it.getType(i)) { Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_INTEGER -> it.getLong(i); else -> it.getString(i) } }
            } while (it.moveToNext())
        }
        return result to false
    }
}
