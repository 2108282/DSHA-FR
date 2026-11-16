package com.deepseekharness.app.phone.tasks

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import java.util.ArrayList

internal class TaskNativeTelephony(private val context: Context, private val ledger: TaskNativeLedger) {
    fun accounts(): Map<String, Any?> {
        permission(Manifest.permission.READ_PHONE_STATE)
        val telecom = context.getSystemService(TelecomManager::class.java)
        // Handles are sufficient for placing a call. getPhoneAccount() additionally requires
        // READ_PHONE_NUMBERS on target SDK 31+; do not request that merely to fetch a label.
        val accounts = checkedRead("TelecomManager.getCallCapablePhoneAccounts") {
            telecom?.callCapablePhoneAccounts.orEmpty()
        }.map { handle ->
            mapOf("phone_account_id" to handle.id, "component" to handle.componentName.flattenToString())
        }
        val subscriptions = checkedRead("SubscriptionManager.getActiveSubscriptionInfoList") {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
        }.map {
            mapOf("subscription_id" to it.subscriptionId, "slot_index" to it.simSlotIndex, "display_name" to it.displayName.toString())
        }
        return taskNativeResult("intent.accounts", "observed", "已读取可选通话账户与活动SIM，请明确选择；没有自动选择", verified = true, data = mapOf("phone_accounts" to accounts, "subscriptions" to subscriptions))
    }
    fun prepareCall(a: TaskNativeArgs): () -> Map<String, Any?> {
        permission(Manifest.permission.CALL_PHONE)
        val number = TaskNativeRules.number(a.text("number", true, 80)!!)
        val telecom = context.getSystemService(TelecomManager::class.java) ?: fail("UNSUPPORTED", "没有Telecom服务")
        val phone = context.getSystemService(android.telephony.TelephonyManager::class.java)
        if (phone == null || phone.isEmergencyNumber(number)) fail("NEEDS_USER", "紧急号码或无法判定的号码交由用户和系统电话界面处理")
        // Do not silently choose an account or SIM. Querying accounts itself can require READ_PHONE_STATE.
        permission(Manifest.permission.READ_PHONE_STATE)
        val accounts = checkedRead("TelecomManager.getCallCapablePhoneAccounts") { telecom.callCapablePhoneAccounts }
        if (accounts.isEmpty()) fail("NO_CALL_ACCOUNT", "没有可用通话账户")
        val accountId = a.text("phone_account_id", false, 200)
        val account = if (accountId == null) {
            if (accounts.size != 1) fail("PHONE_ACCOUNT_REQUIRED", "存在多个通话账户，请明确phone_account_id")
            accounts.single()
        } else accounts.singleOrNull { it.id == accountId } ?: fail("PHONE_ACCOUNT_INVALID", "指定通话账户不可用或不唯一")
        val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account) }
        return {
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
            taskNativeResult("intent.call", "dispatched", "已向系统提交拨号请求；振铃、接通及结束状态未知，不会自动重拨", "possible", data = mapOf("call_state" to "unknown", "phone_account_id" to account.id, "verification_scope" to "call_request_submitted_not_connected"))
        }
    }
    fun prepareSms(a: TaskNativeArgs, requestId: String): () -> Map<String, Any?> {
        permission(Manifest.permission.SEND_SMS)
        permission(Manifest.permission.READ_PHONE_STATE)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) fail("UNSUPPORTED", "设备没有短信功能")
        val number = TaskNativeRules.number(a.text("number", true, 80)!!)
        val text = a.text("text", true, 10000)!!
        val subscription = a.int("subscription_id", -1, 0, Int.MAX_VALUE)
        if (subscription < 0 || !SubscriptionManager.isValidSubscriptionId(subscription)) fail("SUBSCRIPTION_REQUIRED", "sms_send必须明确subscription_id，不使用默认SIM")
        val subscriptions = checkedRead("SubscriptionManager.getActiveSubscriptionInfoList") {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
        }
        if (subscriptions.none { it.subscriptionId == subscription }) fail("SUBSCRIPTION_INVALID", "指定subscription_id不在当前活动SIM中")
        val manager = context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(subscription) ?: fail("UNSUPPORTED", "没有短信服务")
        val parts = manager.divideMessage(text)
        if (parts.isEmpty() || parts.size > 100) fail("INVALID_ARGUMENT", "短信分段数量无效")
        // Sent callbacks need only resultCode and remain immutable. Delivery callbacks need
        // Android's attached status-report PDU: use an explicit mutable PI, validate its token,
        // and parse SmsMessage.status instead of treating callback dispatch as delivery.
        if (!context.packageManager.getReceiverInfo(android.content.ComponentName(context, TaskNativeSmsReceiver::class.java), 0).enabled) fail("RECEIVER_UNAVAILABLE", "短信回执接收器未启用")
        return {
            val token = ledger.configureSms(requestId, parts.size)
            fun pending(kind: String, part: Int): PendingIntent {
                val i = Intent(context, TaskNativeSmsReceiver::class.java).apply {
                    action = "${context.packageName}.tasknative.SMS_${kind.uppercase()}"
                    data = Uri.Builder().scheme("task-native-receipt").authority(context.packageName).appendPath(token).appendPath(kind).appendPath(part.toString()).build()
                    putExtra(TaskNativeSmsReceiver.REQUEST_ID, requestId); putExtra(TaskNativeSmsReceiver.TOKEN, token)
                    putExtra(TaskNativeSmsReceiver.PART, part); putExtra(TaskNativeSmsReceiver.KIND, kind)
                }
                return PendingIntent.getBroadcast(context, 0, i,
                    if (kind == "delivery") PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            }
            val sent = ArrayList((parts.indices).map { pending("sent", it) })
            // Delivery reports include a PDU supplied by Android. Keep them explicit but mutable
            // so the framework can attach the report; fixed opaque token/identity is revalidated.
            val delivery = ArrayList((parts.indices).map { pending("delivery", it) })
            manager.sendMultipartTextMessage(number, null, parts, sent, delivery)
            taskNativeResult("intent.sms_send", "dispatched", "已向系统提交短信；发送和送达结果请按request_id查询，不会自动重发", "possible", data = mapOf("parts" to parts.size, "subscription_id" to subscription, "sending_status" to "unknown", "delivery_status" to "unknown"))
        }
    }
    private fun <T> checkedRead(api: String, block: () -> T): T = try {
        block()
    } catch (_: SecurityException) {
        fail("ANDROID_ACCESS_DENIED", "$api 被系统拒绝；READ_PHONE_STATE 已通过本地检查，不能据此认定用户未授权。未拨号或发送短信。")
    }
    private fun permission(p: String) { if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) fail("PERMISSION_REQUIRED", "缺少权限 $p；未请求授权") }
}

/** Register exported=false in the Manifest; explicit PendingIntent delivery needs no intent-filter. */
class TaskNativeSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(REQUEST_ID) ?: return
        val token = intent.getStringExtra(TOKEN) ?: return
        val kind = intent.getStringExtra(KIND) ?: return
        val part = intent.getIntExtra(PART, -1)
        val code = if (kind == "delivery") {
            val pdu = intent.getByteArrayExtra("pdu") ?: return
            val format = intent.getStringExtra("format") ?: return
            val message = runCatching { android.telephony.SmsMessage.createFromPdu(pdu, format) }.getOrNull() ?: return
            val status = message.status
            // 0..31 complete, 32..63 pending, 64..127 permanent failure. Unknown stays unknown.
            when { status in 0..31 -> 0; status in 64..127 -> status; else -> return }
        } else resultCode
        TaskNativeLedger(context.applicationContext).receipt(id, token, part, kind, code)
    }
    companion object {
        internal const val REQUEST_ID = "request_id"
        internal const val TOKEN = "receipt_token"
        internal const val PART = "part"
        internal const val KIND = "kind"
    }
}
