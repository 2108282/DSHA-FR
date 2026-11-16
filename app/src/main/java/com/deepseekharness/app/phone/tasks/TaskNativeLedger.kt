package com.deepseekharness.app.phone.tasks

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Process-wide serialization plus a durable attempted marker; deliberately no exactly-once claim. */
internal class TaskNativeLedger private constructor(private val store: Store) {
    constructor(context: Context) : this(PreferencesStore(context.applicationContext))
    internal constructor(read: (String) -> String?, write: (String, String) -> Boolean) : this(object : Store {
        override fun read(id: String) = read.invoke(id)
        override fun write(id: String, value: String) = write.invoke(id, value)
    })
    private interface Store { fun read(id: String): String?; fun write(id: String, value: String): Boolean }
    private class PreferencesStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("task_native_operations_v1", Context.MODE_PRIVATE)
        override fun read(id: String) = prefs.getString(id, null)
        override fun write(id: String, value: String) = prefs.edit().putString(id, value).commit()
    }
    fun replay(id: String, digest: String): Map<String, Any?>? = synchronized(lock) {
        existing(id)?.let { prior ->
            if (prior.optString("digest") != digest) taskNativeResult(prior.optString("action"), "denied", "request_id已用于其他参数，未执行", reason = "REQUEST_ID_CONFLICT")
            else result(id, prior) + ("deduplicated" to true)
        }
    }
    fun run(id: String, digest: String, action: String, beforeWrite: () -> Boolean = { !Thread.currentThread().isInterrupted }, block: () -> Map<String, Any?>): Map<String, Any?> = synchronized(lock) {
        existing(id)?.let { prior ->
            if (prior.optString("digest") != digest) return@synchronized taskNativeResult(action, "denied", "request_id 已用于不同参数，未执行", reason = "REQUEST_ID_CONFLICT")
            return@synchronized result(id, prior) + ("deduplicated" to true)
        }
        if (!beforeWrite()) return@synchronized taskNativeResult(action, "denied", "任务已停止、过期或设备状态不允许写入，未执行", reason = "TASK_WRITE_CANCELLED")
        val record = JSONObject().put("digest", digest).put("action", action).put("created_at", System.currentTimeMillis())
            .put("status", "unconfirmed").put("effect", "possible").put("text", "该请求已登记，执行结果尚未确认；不要重新发送")
        if (!save(id, record)) return@synchronized taskNativeResult(action, "failed", "无法保存防重复标记，未执行", reason = "LEDGER_UNAVAILABLE")
        val out = try {
            if (!beforeWrite()) taskNativeResult(action, "unconfirmed", "请求已登记后任务停止，未发起新写入；相同request_id不会重放", "none", reason = "TASK_WRITE_CANCELLED")
            else block()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            taskNativeResult(action, "unconfirmed", "原生调用被中断，动作结果未知，不会重放", "possible", reason = "TASK_INTERRUPTED")
        } catch (e: Exception) {
            taskNativeResult(action, "unconfirmed", "动作可能已执行，未收到完整结果；先查询状态", "possible", reason = if (e is TaskNativeFault) e.code else "NATIVE_RESULT_UNKNOWN")
        }
        // SMS callbacks can arrive before sendMultipartTextMessage returns. Keep their receipts.
        val latest = existing(id) ?: record
        out.forEach { (key, value) -> latest.put(key, JSONObject.wrap(value)) }
        save(id, latest)
        result(id, latest)
    }
    fun status(id: String): Map<String, Any?> = synchronized(lock) {
        existing(id)?.let { result(id, it) } ?: taskNativeResult("operation_status", "unconfirmed", "没有该request_id的记录；未知不代表未执行，请勿据此重发", "unknown", reason = "REQUEST_NOT_FOUND") + ("request_id" to id)
    }
    fun configureSms(id: String, count: Int): String = synchronized(lock) {
        val r = existing(id) ?: fail("LEDGER_UNAVAILABLE", "发送记录不存在")
        val token = UUID.randomUUID().toString()
        r.put("receipt_token", token).put("parts", count).put("sent", JSONObject()).put("delivery", JSONObject())
        if (!save(id, r)) fail("LEDGER_UNAVAILABLE", "无法保存短信回执信息")
        token
    }
    fun receipt(id: String, token: String, part: Int, kind: String, code: Int) = synchronized(lock) {
        val r = existing(id) ?: return@synchronized
        if (token != r.optString("receipt_token") || kind !in setOf("sent", "delivery") || part !in 0 until r.optInt("parts")) return@synchronized
        val receipts = r.optJSONObject(kind) ?: JSONObject()
        if (!receipts.has(part.toString())) receipts.put(part.toString(), code)
        r.put(kind, receipts)
        save(id, r)
    }
    private fun result(id: String, r: JSONObject): Map<String, Any?> {
        val out = TaskNativeJson.map(r).filterKeys { it !in setOf("digest", "receipt_token", "sent", "delivery") }.toMutableMap()
        out["request_id"] = id
        if (!out.containsKey("backend")) out["backend"] = "android_native"
        if (!out.containsKey("verified")) out["verified"] = false
        if (!out.containsKey("retry_safe")) out["retry_safe"] = false
        val total = r.optInt("parts", 0)
        if (total > 0) {
            val sent = r.optJSONObject("sent") ?: JSONObject(); val delivery = r.optJSONObject("delivery") ?: JSONObject()
            val sentCodes = (0 until total).map { if (sent.has(it.toString())) sent.optInt(it.toString()) else null }
            val deliveryCodes = (0 until total).map { if (delivery.has(it.toString())) delivery.optInt(it.toString()) else null }
            val receiptState = TaskNativeReceiptPolicy.state(sentCodes, deliveryCodes)
            val delivered = receiptState.delivery == "delivered"
            val sentAll = receiptState.sending == "sent"
            val sentFailed = receiptState.sending == "partial_or_failed"
            out["sent_parts"] = sentCodes
            out["delivery_parts"] = (0 until total).map { if (delivery.has(it.toString())) delivery.optInt(it.toString()) else null }
            out["sending_status"] = when { sentAll -> "sent"; sentFailed -> "partial_or_failed"; else -> "unknown" }
            out["delivery_status"] = if (delivered) "delivered" else "unknown"
            if (sentAll || delivered) {
                out["status"] = "completed"; out["verified"] = true; out["effect"] = "applied"
                out["verification_scope"] = if (delivered) "sms_delivery_receipts" else "sms_sent_receipts"
                out["text"] = if (delivered) "短信各分段已收到送达回执" else "短信各分段已收到发送成功回执；送达状态另见delivery_status"
            } else if (sentFailed) {
                out["status"] = "unconfirmed"; out["effect"] = "possible"; out["verified"] = false
                out["text"] = "短信存在失败分段，其他分段可能已发出；不要自动重发"
            }
            out["retry_safe"] = false
        }
        return out
    }
    private fun existing(id: String): JSONObject? = store.read(id)?.let { raw ->
        runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("digest", "corrupt_record").put("action", "unknown").put("status", "unconfirmed").put("effect", "unknown").put("text", "已有记录损坏，禁止使用相同request_id重试") }
    }
    private fun save(id: String, record: JSONObject) = store.write(id, record.toString())
    companion object { private val lock = Any() }
}

internal object TaskNativeJson {
    fun map(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { unwrap(value.opt(it)) }
    private fun unwrap(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> map(v)
        is org.json.JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
        else -> v
    }
}
