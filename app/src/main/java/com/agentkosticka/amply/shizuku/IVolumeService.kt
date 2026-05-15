package com.agentkosticka.amply.shizuku

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * Manual Binder interface for VolumeService (replaces AIDL to avoid JDK 24 issues)
 *
 * Transaction codes:
 * - TRANSACTION_getActivePlaybacks = 1
 * - TRANSACTION_setPlayerVolume = 2
 * - TRANSACTION_destroy = 3
 */
interface IVolumeService : IInterface {

    companion object {
        const val DESCRIPTOR = "com.agentkosticka.amply.shizuku.IVolumeService"
        const val TRANSACTION_getActivePlaybacks = IBinder.FIRST_CALL_TRANSACTION + 0
        const val TRANSACTION_setPlayerVolume = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_destroy = IBinder.FIRST_CALL_TRANSACTION + 2

        fun asInterface(binder: IBinder?): IVolumeService? {
            if (binder == null) return null
            val iin = binder.queryLocalInterface(DESCRIPTOR)
            return if (iin is IVolumeService) {
                iin
            } else {
                Proxy(binder)
            }
        }
    }

    /**
     * Gets active playback configurations with full data (uid, pid, piid)
     * Returns a list of PlaybackInfo objects serialized as: [count, piid1, uid1, pid1, piid2, uid2, pid2, ...]
     */
    fun getActivePlaybacks(): IntArray

    /**
     * Sets volume for a player by piid
     * @param piid Player interface ID
     * @param volume Volume from 0.0 to 1.0
     * @return true if successful
     */
    fun setPlayerVolume(piid: Int, volume: Float): Boolean

    /**
     * Destroys the service
     */
    fun destroy()

    /**
     * Stub implementation for the service side
     */
    abstract class Stub : Binder(), IVolumeService {
        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    return true
                }
                TRANSACTION_getActivePlaybacks -> {
                    data.enforceInterface(DESCRIPTOR)
                    val result = getActivePlaybacks()
                    reply?.writeNoException()
                    reply?.writeIntArray(result)
                    return true
                }
                TRANSACTION_setPlayerVolume -> {
                    data.enforceInterface(DESCRIPTOR)
                    val piid = data.readInt()
                    val volume = data.readFloat()
                    val result = setPlayerVolume(piid, volume)
                    reply?.writeNoException()
                    reply?.writeInt(if (result) 1 else 0)
                    return true
                }
                TRANSACTION_destroy -> {
                    data.enforceInterface(DESCRIPTOR)
                    destroy()
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /**
     * Proxy implementation for the client side
     */
    class Proxy(private val remote: IBinder) : IVolumeService {
        override fun asBinder(): IBinder = remote

        override fun getActivePlaybacks(): IntArray {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                remote.transact(TRANSACTION_getActivePlaybacks, data, reply, 0)
                reply.readException()
                reply.createIntArray() ?: IntArray(0)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun setPlayerVolume(piid: Int, volume: Float): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(piid)
                data.writeFloat(volume)
                remote.transact(TRANSACTION_setPlayerVolume, data, reply, 0)
                reply.readException()
                reply.readInt() != 0
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun destroy() {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                remote.transact(TRANSACTION_destroy, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
