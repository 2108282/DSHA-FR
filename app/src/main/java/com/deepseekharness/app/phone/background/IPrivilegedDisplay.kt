package com.deepseekharness.app.phone.background

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

// A hand-written Binder (not AIDL, so no `buildFeatures { aidl }` and so the whole
// contract type-checks as plain Kotlin). One method: a request Bundle in, a response
// Bundle out. probe / release / destroy are just ops inside the Bundle (see Proto), so
// the Shizuku binder and the adb stream carry exactly the same payloads.
interface IPrivilegedDisplay : IInterface {
    fun execute(request: Bundle): Bundle

    abstract class Stub : Binder(), IPrivilegedDisplay {
        init { attachInterface(this, DESCRIPTOR) }
        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TXN_EXECUTE) {
                data.enforceInterface(DESCRIPTOR)
                val request = data.readBundle(javaClass.classLoader) ?: Bundle()
                val result = execute(request)
                reply?.writeNoException()
                reply?.writeBundle(result)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }

        private class Proxy(private val remote: IBinder) : IPrivilegedDisplay {
            override fun asBinder(): IBinder = remote
            override fun execute(request: Bundle): Bundle {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeBundle(request)
                    remote.transact(TXN_EXECUTE, data, reply, 0)
                    reply.readException()
                    return reply.readBundle(javaClass.classLoader) ?: Bundle()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }

        companion object {
            fun asInterface(binder: IBinder?): IPrivilegedDisplay? {
                if (binder == null) return null
                val local = binder.queryLocalInterface(DESCRIPTOR)
                return if (local is IPrivilegedDisplay) local else Proxy(binder)
            }
        }
    }

    companion object {
        const val DESCRIPTOR = "com.deepseekharness.app.phone.background.IPrivilegedDisplay"
        const val TXN_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
    }
}
