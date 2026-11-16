package com.deepseekharness.app.phone.background

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Pure state shared by the driver and privileged endpoint. Display text is never an identity. */
internal object BackgroundRepairIdentity {
    private val sequence = AtomicLong(System.currentTimeMillis() * 1000)
    fun screen(): String = "b${sequence.incrementAndGet()}"
    fun owner(taskId: Long): String = "$taskId:${UUID.randomUUID()}"
    fun digest(vararg fields: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(fields.joinToString("\u0000") { value ->
            val text = value?.toString().orEmpty()
            "${text.length}:$text"
        }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal class BackgroundRepairSnapshots {
    private var owner: String? = null
    private var token: String? = null
    private var fingerprints: List<String> = emptyList()
    fun publish(owner: String, fingerprints: List<String>): String {
        this.owner = owner
        this.fingerprints = fingerprints.toList()
        return UUID.randomUUID().toString().also { token = it }
    }
    fun accepts(owner: String, token: String?, index: Int? = null, fingerprint: String? = null): Boolean =
        this.owner == owner && token != null && this.token == token &&
            (index == null || index > 0 && fingerprint != null && fingerprints.getOrNull(index - 1) == fingerprint)
    fun clear() { owner = null; token = null; fingerprints = emptyList() }
}
