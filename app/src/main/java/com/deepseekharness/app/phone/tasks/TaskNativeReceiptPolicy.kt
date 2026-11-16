package com.deepseekharness.app.phone.tasks

/** Pure receipt state: missing or pending is never interpreted as safe-to-resend. */
internal object TaskNativeReceiptPolicy {
    data class State(val sending: String, val delivery: String, val verified: Boolean, val scope: String?)
    fun state(sent: List<Int?>, delivery: List<Int?>): State {
        val allSent = sent.isNotEmpty() && sent.all { it == -1 }
        val failed = sent.any { it != null && it != -1 }
        val allDelivered = delivery.isNotEmpty() && delivery.all { it == 0 }
        return State(if (allSent) "sent" else if (failed) "partial_or_failed" else "unknown", if (allDelivered) "delivered" else "unknown", allSent || allDelivered, if (allDelivered) "sms_delivery_receipts" else if (allSent) "sms_sent_receipts" else null)
    }
}
