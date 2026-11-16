package com.deepseekharness.app.phone.tasks

import android.content.Context
import android.content.pm.PackageManager

/**
 * Integration boundary: call only after existing task/lock/policy/confirmation checks.
 * Never asks for permissions, never invokes GUI, and never accepts arbitrary provider/SQL.
 * Use one facade per app; the durable write ledger and receipt updates are process-wide synchronized.
 */
class TaskNativeFacade(context: Context) {
    private val app = context.applicationContext
    private val ledger = TaskNativeLedger(app)
    private val calendar = TaskNativeCalendar(app)
    private val queries = TaskNativeQueries(app)
    private val telephony = TaskNativeTelephony(app, ledger)

    fun execute(tool: String, args: Map<String, Any?>, beforeWrite: () -> Boolean = { !Thread.currentThread().isInterrupted }): Map<String, Any?> {
        val a = TaskNativeArgs(args)
        val action = if (tool == "intent") args["kind"]?.toString().orEmpty() else args["action"]?.toString().orEmpty()
        val name = if (action.isBlank()) tool else "$tool.$action"
        return try {
            if (tool == "intent" && action == "accounts") return telephony.accounts()
            if (tool == "operation_status" || tool == "intent" && action == "operation_status") return operationStatus(a.text("request_id", true, 160)!!)
            val write = tool == "calendar" && action in setOf("create", "create_many", "update", "delete") || tool == "intent" && action in setOf("call", "sms_send")
            if (!write) return when (tool) {
                "contacts" -> queries.contacts(a)
                "messages" -> queries.messages(a)
                "media" -> queries.media(a)
                "calendar" -> calendar.prepare(a).invoke()
                else -> taskNativeResult(name, "unsupported", "TaskNative不处理此工具或事务", reason = "UNSUPPORTED_ACTION")
            }
            val requestId = a.text("request_id", true, 160)!!
            if (!Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}").matches(requestId)) fail("INVALID_ARGUMENT", "request_id格式无效")
            val digest = TaskNativeRules.fingerprint(tool, args)
            ledger.replay(requestId, digest)?.let { return it }
            // All argument parsing, permission checks and non-mutating lookups happen before run().
            val prepared = when {
                tool == "calendar" -> calendar.prepare(a)
                action == "call" -> telephony.prepareCall(a)
                else -> telephony.prepareSms(a, requestId)
            }
            ledger.run(requestId, digest, name, beforeWrite, prepared)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            taskNativeResult(name, "denied", "原生预检查被中断，未发起新写入", reason = "TASK_INTERRUPTED")
        } catch (e: TaskNativeFault) {
            taskNativeResult(name, if (e.code.contains("PERMISSION") || e.code.contains("REQUIRED") || e.code == "NEEDS_USER" || e.code == "ANDROID_ACCESS_DENIED") "denied" else "failed", e.message, reason = e.code)
        } catch (_: SecurityException) {
            taskNativeResult(name, "denied", "Android拒绝访问；没有请求授权或切换执行通道", reason = "ANDROID_ACCESS_DENIED")
        } catch (_: PackageManager.NameNotFoundException) {
            taskNativeResult(name, "unsupported", "短信回执Receiver尚未注册，未执行", reason = "SMS_RECEIVER_NOT_REGISTERED")
        } catch (_: Exception) {
            taskNativeResult(name, "failed", "原生预检查或读取失败；未发起新的写动作", reason = "NATIVE_PRECHECK_FAILED")
        }
    }
    fun operationStatus(requestId: String): Map<String, Any?> {
        if (requestId.isBlank() || requestId.length > 160) return taskNativeResult("operation_status", "failed", "request_id无效", reason = "INVALID_ARGUMENT")
        return ledger.status(requestId)
    }
    companion object {
        /** Presentation only; permission and policies remain the integrator's responsibility. */
        @JvmStatic fun describe(tool: String, args: Map<String, Any?>): String = when (tool) {
            "intent" -> when (args["kind"]) {
                "call" -> "拨打 ${args["number"] ?: "指定号码"}"
                "sms_send" -> "向 ${args["number"] ?: "指定号码"} 发送短信（${(args["text"] as? String)?.length ?: 0}字，SIM ${args["subscription_id"] ?: "未指定"}）"
                else -> "查询原生事务状态"
            }
            "calendar" -> "日历 ${args["action"] ?: "操作"} ${args["event_id"] ?: args["title"] ?: ""}"
            else -> "$tool ${args["action"] ?: "查询"}"
        }
    }
}
