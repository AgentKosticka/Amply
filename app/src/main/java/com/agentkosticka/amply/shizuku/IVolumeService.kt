package com.agentkosticka.amply.shizuku

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException

/**
 * Manual Binder interface for VolumeService (replaces AIDL to avoid JDK 24 issues)
 *
 * Transaction codes:
 * - TRANSACTION_getActivePlaybacks = 1
 * - TRANSACTION_setPlayerVolume = 2
 * - TRANSACTION_applyRingerExperiment = 3
 * - TRANSACTION_destroy = 4
 * - TRANSACTION_getStreamTopology = 5
 * - TRANSACTION_setSystemStreamVolume = 6
 */
interface IVolumeService : IInterface {

    companion object {
        const val DESCRIPTOR = "com.agentkosticka.amply.shizuku.IVolumeService"
        const val TRANSACTION_getActivePlaybacks = IBinder.FIRST_CALL_TRANSACTION + 0
        const val TRANSACTION_setPlayerVolume = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_applyRingerExperiment = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_destroy = IBinder.FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_getStreamTopology = IBinder.FIRST_CALL_TRANSACTION + 4
        const val TRANSACTION_setSystemStreamVolume = IBinder.FIRST_CALL_TRANSACTION + 5
        const val TRANSACTION_destroyUserService = 16777115

        fun asInterface(binder: IBinder?): IVolumeService? {
            if (binder == null) return null
            val iin = binder.queryLocalInterface(DESCRIPTOR)
            return iin as? IVolumeService ?: Proxy(binder)
        }
    }

    /**
     * Gets active playback configurations with full data (uid, pid, piid)
     * Serialized as [count, piid, uid, pid, state, legacyStreamType, ...].
     */
    fun getActivePlaybacks(): IntArray

    /**
     * Sets volume for a player by piid
     * @param piid Player interface ID
     * @param volume Volume from 0.0 to 1.0
     * @return true if successful
     */
    fun setPlayerVolume(piid: Int, volume: Float): Boolean

    fun applyRingerExperiment(method: Int, target: Int, restoreVolume: Int): Int

    /** [knownFlag, count, aliasForStream0, aliasForStream1, ...]. */
    fun getStreamTopology(): IntArray

    /** Returns a positive status on success and a negative status on failure. */
    fun setSystemStreamVolume(streamType: Int, index: Int): Int

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
                TRANSACTION_applyRingerExperiment -> {
                    data.enforceInterface(DESCRIPTOR)
                    val result = applyRingerExperiment(data.readInt(), data.readInt(), data.readInt())
                    reply?.writeNoException()
                    reply?.writeInt(result)
                    return true
                }
                TRANSACTION_getStreamTopology -> {
                    data.enforceInterface(DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeIntArray(getStreamTopology())
                    return true
                }
                TRANSACTION_setSystemStreamVolume -> {
                    data.enforceInterface(DESCRIPTOR)
                    val result = setSystemStreamVolume(data.readInt(), data.readInt())
                    reply?.writeNoException()
                    reply?.writeInt(result)
                    return true
                }
                TRANSACTION_destroy,
                TRANSACTION_destroyUserService -> {
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
                if (!remote.transact(TRANSACTION_getActivePlaybacks, data, reply, 0)) {
                    throw RemoteException("Volume service rejected playback query")
                }
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
                if (!remote.transact(TRANSACTION_setPlayerVolume, data, reply, 0)) {
                    throw RemoteException("Volume service rejected volume update")
                }
                reply.readException()
                reply.readInt() != 0
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun applyRingerExperiment(method: Int, target: Int, restoreVolume: Int): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(method)
                data.writeInt(target)
                data.writeInt(restoreVolume)
                if (!remote.transact(TRANSACTION_applyRingerExperiment, data, reply, 0)) {
                    throw RemoteException("Volume service rejected ringer experiment")
                }
                reply.readException()
                reply.readInt()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun getStreamTopology(): IntArray {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                if (!remote.transact(TRANSACTION_getStreamTopology, data, reply, 0)) {
                    throw RemoteException("Volume service rejected stream-topology query")
                }
                reply.readException()
                reply.createIntArray() ?: IntArray(0)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun setSystemStreamVolume(streamType: Int, index: Int): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(streamType)
                data.writeInt(index)
                if (!remote.transact(TRANSACTION_setSystemStreamVolume, data, reply, 0)) {
                    throw RemoteException("Volume service rejected system-stream update")
                }
                reply.readException()
                reply.readInt()
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
                if (!remote.transact(TRANSACTION_destroy, data, reply, 0)) {
                    throw RemoteException("Volume service rejected destroy request")
                }
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
