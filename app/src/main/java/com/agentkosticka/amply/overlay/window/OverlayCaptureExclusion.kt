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
 * HiddenApiBypass in its Application before this code can run.
 *
 * Important: a successful reflective transaction only proves that the transaction was submitted.
 * It does not prove that an OEM screenshot implementation will honor SKIP_SCREENSHOT, and a window
 * relayout can replace the native SurfaceControl behind the stable Java wrapper. For that reason we
 * deliberately tell the caller to retain FLAG_SECURE whenever capture exclusion is enabled, and we
 * re-resolve/reapply the flag on the next frame instead of caching by Java object identity.
 */
internal class OverlayCaptureExclusion {
    private var unavailableLogged = false

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    fun setEnabled(view: View, enabled: Boolean): Boolean {
        val applied = applyOnce(view, enabled, logSuccess = true)

        // configureCaptureExclusion() can be followed immediately by a WindowManager relayout
        // (unpark/position/flag update). Re-resolve the SurfaceControl after that work has had a
        // chance to land so SKIP_SCREENSHOT is also applied to a replacement native surface.
        if (applied) {
            view.postOnAnimation {
                applyOnce(view, enabled, logSuccess = false)
            }
        }

        // OverlayManager interprets true as permission to drop FLAG_SECURE. Keep the supported
        // secure-window fallback while exclusion is enabled even when this hidden API did not throw.
        return applied && !enabled
    }

    fun reset() {
        unavailableLogged = false
    }

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    private fun applyOnce(view: View, enabled: Boolean, logSuccess: Boolean): Boolean {
        val surface = runCatching { viewRootSurface(view) }.getOrElse { error ->
            logUnavailable(error)
            return false
        } ?: run {
            logUnavailable(IllegalStateException("Overlay surface is not attached"))
            return false
        }

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
            unavailableLogged = false
            if (logSuccess) {
                Log.i(
                    TAG,
                    "SKIP_SCREENSHOT ${if (enabled) "submitted" else "cleared"}; FLAG_SECURE fallback retained when enabled"
                )
            }
            true
        }.getOrElse { error ->
            logUnavailable(error)
            false
        }
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
        Log.w(TAG, "SKIP_SCREENSHOT unavailable; FLAG_SECURE remains active", error)
    }

    private companion object {
        const val TAG = "OverlayCapture"
        const val SURFACE_CONTROL_CLASS = "android.view.SurfaceControl"
        const val TRANSACTION_CLASS = "android.view.SurfaceControl\$Transaction"
    }
}
