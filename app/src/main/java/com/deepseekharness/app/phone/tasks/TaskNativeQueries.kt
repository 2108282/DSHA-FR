package com.deepseekharness.app.phone.tasks

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony

internal class TaskNativeQueries(private val context: Context) {
    private val resolver get() = context.contentResolver
    fun contacts(a: TaskNativeArgs): Map<String, Any?> {
        requirePermission(Manifest.permission.READ_CONTACTS)
        val action = a.text("action", true, 30)!!
        val page = a.page()
        val query = a.text("query", action == "search", 200)
        val id = if (action == "get") a.id("contact_id") else null
        if (action !in setOf("search", "get")) fail("INVALID_ARGUMENT", "contacts action应为search/get")
        val uri = if (action == "search") Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query!!)) else ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id!!)
        val (rows, more) = query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.LOOKUP_KEY), null, null, "${ContactsContract.Contacts._ID} ASC", page)
        val items = rows.map { row ->
            val cid = row["_id"].toString()
            val phones = query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(cid), "${ContactsContract.CommonDataKinds.Phone._ID} ASC", TaskNativePage(0, 50))
            row + mapOf("contact_id" to cid, "numbers" to phones.first.distinctBy { it["data4"] ?: it["data1"] }.map { phone ->
                mapOf("number" to phone["data1"], "normalized_number" to phone["data4"], "type" to phone["data2"], "label" to phone["data3"])
            }, "numbers_truncated" to phones.second)
        }
        return taskNativeResult("contacts.$action", "observed", "已读取联系人；重名或多号码需要明确选择", verified = true, data = mapOf("items" to items, "ambiguous" to (more || items.size != 1 || (items.firstOrNull()?.get("numbers") as? List<*>)?.size != 1 || items.firstOrNull()?.get("numbers_truncated") == true), "offset" to page.offset, "has_more" to more, "next_offset" to if (more) page.offset + items.size else null))
    }
    fun messages(a: TaskNativeArgs): Map<String, Any?> {
        requirePermission(Manifest.permission.READ_SMS)
        val action = a.text("action", true, 30)!!
        if (action == "threads") return messageThreads(a)
        if (action !in setOf("search", "read")) fail("INVALID_ARGUMENT", "messages action应为threads/search/read")
        val thread = a.id("thread_id")
        val page = a.page()
        val q = a.text("query", false, 200)
        val selection = "thread_id=?" + if (q != null) " AND body LIKE ? ESCAPE '\\'" else ""
        val params = if (q != null) arrayOf(thread.toString(), TaskNativeRules.like(q)) else arrayOf(thread.toString())
        val (items, more) = query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "thread_id", "address", "body", "date", "type", "read"), selection, params, "date DESC, _id DESC", page)
        return taskNativeResult("messages.$action", "observed", "已读取指定短信会话的一页消息", verified = true, data = mapOf("thread_id" to thread.toString(), "items" to items, "offset" to page.offset, "next_offset" to if (more) page.offset + items.size else null, "has_more" to more, "verification_scope" to "provider_query_page"))
    }
    private fun messageThreads(a: TaskNativeArgs): Map<String, Any?> {
        val number = a.text("number", false, 80)?.let(TaskNativeRules::number)
        val query = a.text("query", number == null, 100)
        if (number == null && (query?.length ?: 0) < 3) fail("INVALID_ARGUMENT", "地址query至少3字，或提供明确number")
        val page = a.page()
        val selection = if (number != null) "address=?" else "address LIKE ? ESCAPE '\\'"
        val params = arrayOf(number ?: TaskNativeRules.like(query!!))
        val c = resolver.query(Telephony.Sms.CONTENT_URI, arrayOf("thread_id", "address", "date"), selection, params, "date DESC, _id DESC") ?: fail("PROVIDER_UNAVAILABLE", "短信未返回会话结果")
        val unique = LinkedHashMap<String, Map<String, Any?>>()
        var scanned = 0; var exhausted = true
        c.use {
            while (it.moveToNext()) {
                if (++scanned > 500) { exhausted = false; break }
                val id = it.getLong(0).toString()
                if (!unique.containsKey(id)) unique[id] = mapOf("thread_id" to id, "address" to it.getString(1), "last_message_at" to it.getLong(2))
                if (unique.size > page.offset + page.limit) { exhausted = false; break }
            }
        }
        val items = unique.values.drop(page.offset).take(page.limit)
        val more = unique.size > page.offset + page.limit
        return taskNativeResult("messages.threads", "observed", "已查询指定地址的会话；号码格式以短信Provider存储为准", verified = true, data = mapOf("items" to items, "has_more" to more, "next_offset" to if (more) page.offset + items.size else null, "scan_truncated" to (!exhausted && !more), "scanned_messages" to scanned.coerceAtMost(500), "matching" to if (number != null) "exact_provider_address" else "address_contains"))
    }
    fun media(a: TaskNativeArgs): Map<String, Any?> {
        if (a.text("action", false, 30)?.let { it != "search" } == true) fail("INVALID_ARGUMENT", "media只支持search")
        val kind = a.text("kind", true, 20)!!
        val uri = when (kind) {
            "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "files" -> MediaStore.Files.getContentUri("external")
            else -> fail("INVALID_ARGUMENT", "media kind应为images/audio/video/files")
        }
        if (kind != "files") {
            val permission = if (Build.VERSION.SDK_INT >= 33) when (kind) { "images" -> Manifest.permission.READ_MEDIA_IMAGES; "video" -> Manifest.permission.READ_MEDIA_VIDEO; else -> Manifest.permission.READ_MEDIA_AUDIO } else Manifest.permission.READ_EXTERNAL_STORAGE
            val selectedVisual = Build.VERSION.SDK_INT >= 34 && kind in setOf("images", "video") && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!selectedVisual) requirePermission(permission)
        }
        val q = a.text("query", true, 200)!!
        val page = a.page()
        val selection = "_display_name LIKE ? ESCAPE '\\'" + if (kind == "files") " AND media_type=0" else ""
        val (rows, more) = query(uri, arrayOf("_id", "_display_name", "mime_type", "_size", "date_modified"), selection, arrayOf(TaskNativeRules.like(q)), "date_modified DESC, _id DESC", page)
        val items = rows.map { row -> row + ("content_uri" to ContentUris.withAppendedId(uri, row["_id"].toString().toLong()).toString()) }
        return taskNativeResult("media.search", "observed", "已读取媒体元数据；仅包含当前App有权访问的项目", verified = true, data = mapOf("items" to items, "has_more" to more, "next_offset" to if (more) page.offset + items.size else null, "access_scope" to if (kind == "files") "app_accessible_non_media_files_not_all_documents" else "app_authorized_media", "content_uri_authority" to "media"))
    }
    private fun requirePermission(permission: String) { if (context.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) fail("PERMISSION_REQUIRED", "缺少权限 $permission；未请求授权") }
    private fun query(uri: Uri, columns: Array<String>, selection: String?, args: Array<String>?, sort: String, page: TaskNativePage): Pair<List<Map<String, Any?>>, Boolean> {
        val rows = ArrayList<Map<String, Any?>>()
        val cursor = resolver.query(uri, columns, selection, args, sort) ?: fail("PROVIDER_UNAVAILABLE", "数据提供者未返回可读取结果")
        cursor.use {
            if (!it.moveToPosition(page.offset)) return rows to false
            do {
                if (rows.size == page.limit) return rows to true
                val truncatedFields = ArrayList<String>()
                val row = (0 until it.columnCount).associate { i -> it.getColumnName(i) to when (it.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> it.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> it.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> it.getString(i)?.let { value -> if (value.length > 8000) truncatedFields += it.getColumnName(i); value.take(8000) }
                    else -> null
                } }
                rows += row + ("truncated_fields" to truncatedFields)
            } while (it.moveToNext())
        }
        return rows to false
    }
}
