package com.deepseekharness.app.phone.background

import android.os.Bundle

interface PrivilegedChannel {
    fun execute(request: Bundle): Bundle
    fun alive(): Boolean
    fun close()
}

class ShizukuChannel(private val remote: IPrivilegedDisplay) : PrivilegedChannel {
    override fun execute(request: Bundle): Bundle = remote.execute(request)
    override fun alive(): Boolean = runCatching { remote.asBinder().pingBinder() }.getOrDefault(false)
    override fun close() {}
}
