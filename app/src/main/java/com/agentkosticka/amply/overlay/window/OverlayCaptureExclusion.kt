package com.agentkosticka.amply.overlay.window

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Applies SurfaceControl.SKIP_SCREENSHOT to Amply's own overlay surface.
 *
 * The API is hidden, so all framework types involved are resolved reflectively. Amply initializes
 * HiddenApiBypass in its Application before this code can run. Callers must retain FLAG_SECURE until
 * this reports success, because a successful reflection call is the point at which the compositor
 * transaction has been submitted.
 */
internal class OverlayCaptureExclusion {
    private var configuredSurface: Any? = null
    private var configuredEnabled: Boolean? = null
    private var unavailableLogged = false

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    fun setEnabled(view: View, enabled: Boolean): Boolean {
        val surface = runCatching { viewRootSurface(view) }.getOrElse { error ->
            logUnavailable(error)
            return false
        } ?: run {
            logUnavailable(IllegalStateException("Overlay surface is not attached"))
            return false
        }

        if (surface === configuredSurface && configuredEnabled == enabled) return true

        return runCatching {
            val surfaceControlClass = Class.forName(SURFACE_CONTROL_CLASS)
            val transactionClass = Class.forName(TRANSACTION_CLASS)
            val transaction = transactionClass.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance()
            try {
                transactionClass.findMethod(
                    name = "setSkipScreenshot",
                    parameterTypes = arrayOf(surfaceControlClass, Boolean::class.javaPrimitiveType!!)
                ).invoke(transaction, surface, enabled)
                transactionClass.findMethod("apply").invoke(transaction)
            } finally {
                runCatching {
                    val close = transactionClass.findOptionalMethod("close")
                    if (close != null) {
                        close.invoke(transaction)
                    } else {
                        transactionClass.findOptionalMethod("release")?.invoke(transaction)
                    }
                }
            }
            configuredSurface = surface
            configuredEnabled = enabled
            unavailableLogged = false
            Log.i(TAG, "SKIP_SCREENSHOT ${if (enabled) "enabled" else "disabled"} on overlay surface")
            true
        }.getOrElse { error ->
            configuredSurface = null
            configuredEnabled = null
            logUnavailable(error)
            false
        }
    }

    fun reset() {
        configuredSurface = null
        configuredEnabled = null
    }

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    private fun viewRootSurface(view: View): Any? {
        val viewRoot = View::class.java.findMethod("getViewRootImpl").invoke(view) ?: return null
        viewRoot.javaClass.findOptionalMethod("getSurfaceControl")?.let { method ->
            method.invoke(viewRoot)?.let { return it }
        }
        return viewRoot.javaClass.findField("mSurfaceControl").get(viewRoot)
    }

    private fun Class<*>.findMethod(
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray()
    ): Method = findOptionalMethod(name, parameterTypes)
        ?: throw NoSuchMethodException("$name on $this")

    private fun Class<*>.findOptionalMethod(
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray()
    ): Method? {
        var type: Class<*>? = this
        while (type != null) {
            runCatching { type.getDeclaredMethod(name, *parameterTypes) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            type = type.superclass
        }
        return null
    }

    private fun Class<*>.findField(name: String): Field {
        var type: Class<*>? = this
        while (type != null) {
            runCatching { type.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            type = type.superclass
        }
        throw NoSuchFieldException("$name on $this")
    }

    private fun logUnavailable(error: Throwable) {
        if (unavailableLogged) return
        unavailableLogged = true
        Log.w(TAG, "SKIP_SCREENSHOT unavailable; keeping FLAG_SECURE fallback", error)
    }

    private companion object {
        const val TAG = "OverlayCapture"
        const val SURFACE_CONTROL_CLASS = "android.view.SurfaceControl"
        const val TRANSACTION_CLASS = "android.view.SurfaceControl\$Transaction"
    }
}
