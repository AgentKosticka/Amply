package com.agentkosticka.amply.shizuku.server

import android.annotation.SuppressLint
import com.agentkosticka.amply.shizuku.protocol.FractionalVolumeStateParcel
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal data class StreamVolumeSnapshot(
    val minIndex: Int,
    val maxIndex: Int,
    val currentIndex: Int
) {
    val valid: Boolean
        get() = minIndex >= 0 && maxIndex >= minIndex && currentIndex in minIndex..maxIndex
}

internal sealed interface FractionalApplyResult {
    data class Applied(val state: FractionalVolumeStateParcel) : FractionalApplyResult
    data object Unsupported : FractionalApplyResult
    data object Failed : FractionalApplyResult
}

/**
 * Optional native fractional stream-volume adapter.
 *
 * AOSP exposes integer stream indexes. Some OEM builds add the exact static
 * AudioSystem.setStreamVolume(int, float, int) bridge. We use that bridge only after a
 * same-value probe succeeds. There is deliberately no player-volume fallback: player gains
 * belong exclusively to Amply's per-app volume feature.
 */
@SuppressLint("PrivateApi")
internal class FractionalStreamVolumeBackend(
    private val methodResolver: () -> Method? = ::resolveOemSetter,
    private val snapshotReader: (Int) -> StreamVolumeSnapshot?
) {
    private enum class Availability { UNCHECKED, AVAILABLE, UNAVAILABLE }

    private val cachedStates = ConcurrentHashMap<Int, FractionalVolumeStateParcel>()
    private var availability = Availability.UNCHECKED
    private var setter: Method? = null

    @Synchronized
    fun isAvailable(probeStream: Int): Boolean {
        when (availability) {
            Availability.AVAILABLE -> return true
            Availability.UNAVAILABLE -> return false
            Availability.UNCHECKED -> Unit
        }

        val candidate = runCatching { methodResolver() }.getOrNull()
            ?.takeIf(::isCompatibleSetter)
        val snapshot = snapshotReader(probeStream)
        if (candidate == null || snapshot == null || !snapshot.valid) {
            availability = Availability.UNAVAILABLE
            return false
        }

        val applied = invokeSetter(candidate, probeStream, snapshot.currentIndex.toFloat(), snapshot.maxIndex)
        val readback = snapshotReader(probeStream)
        if (!applied || readback == null || !readback.valid ||
            readback.currentIndex != snapshot.currentIndex
        ) {
            availability = Availability.UNAVAILABLE
            setter = null
            return false
        }

        setter = candidate
        availability = Availability.AVAILABLE
        return true
    }

    @Synchronized
    fun apply(streamType: Int, value: Float): FractionalApplyResult {
        if (streamType !in 0..11 || !value.isFinite()) return FractionalApplyResult.Failed
        if (!isAvailable(streamType)) return FractionalApplyResult.Unsupported

        val before = snapshotReader(streamType)
        val method = setter
        if (before == null || !before.valid || method == null ||
            value !in before.minIndex.toFloat()..before.maxIndex.toFloat()
        ) {
            return FractionalApplyResult.Failed
        }

        if (!invokeSetter(method, streamType, value, before.maxIndex)) {
            disable()
            return FractionalApplyResult.Unsupported
        }

        val after = snapshotReader(streamType)
        if (after == null || !after.valid) {
            disable()
            return FractionalApplyResult.Failed
        }

        return FractionalVolumeStateParcel.cached(value, after.currentIndex).also {
            cachedStates[streamType] = it
        }.let(FractionalApplyResult::Applied)
    }

    fun state(streamType: Int): FractionalVolumeStateParcel {
        if (streamType !in 0..11 || availability != Availability.AVAILABLE) {
            return FractionalVolumeStateParcel.unavailable()
        }
        val cached = cachedStates[streamType] ?: return FractionalVolumeStateParcel.unavailable()
        val current = snapshotReader(streamType)?.takeIf { it.valid }
        if (current == null || current.currentIndex != cached.nativeIndex) {
            cachedStates.remove(streamType)
            return FractionalVolumeStateParcel.unavailable()
        }
        return cached
    }

    fun invalidate(streamType: Int) {
        if (streamType in 0..11) cachedStates.remove(streamType)
    }

    @Synchronized
    fun invalidateAll() {
        cachedStates.clear()
    }

    @Synchronized
    private fun disable() {
        availability = Availability.UNAVAILABLE
        setter = null
        cachedStates.clear()
    }

    private fun invokeSetter(method: Method, streamType: Int, value: Float, maxIndex: Int): Boolean {
        if (maxIndex <= 0) return value == 0f
        val normalized = (value / maxIndex.toFloat()).coerceIn(0f, 1f)
        return runCatching {
            (method.invoke(null, streamType, normalized, 0) as? Number)?.toInt() == 0
        }.getOrDefault(false)
    }

    companion object {
        private fun isCompatibleSetter(method: Method): Boolean =
            method.name == "setStreamVolume" &&
                Modifier.isStatic(method.modifiers) &&
                method.returnType == Integer.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE)
                )

        private fun resolveOemSetter(): Method? {
            val method = Class.forName("android.media.AudioSystem").declaredMethods.singleOrNull {
                isCompatibleSetter(it)
            } ?: return null
            method.isAccessible = true
            return method
        }
    }
}
