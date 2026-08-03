package com.agentkosticka.amply.overlay.service

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.agentkosticka.amply.audio.session.OverlayAppEntry
import com.agentkosticka.amply.audio.session.toVolumeTarget
import com.agentkosticka.amply.overlay.ui.OverlayAppPresentation

/** Performs drawable loading/conversion away from the main thread. */
internal class OverlayAppPresenter(context: Context) {
    private val packageManager = context.applicationContext.packageManager
    private val iconCache = LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(32)

    fun present(apps: List<OverlayAppEntry>): List<OverlayAppPresentation> = apps.map { app ->
        val cacheKey = app.identity.storageKey
        val icon = iconCache.get(cacheKey) ?: runCatching {
            (app.appIcon ?: packageManager.getApplicationIcon(app.packageName))
                .toBitmap(52, 52)
                .asImageBitmap()
        }.getOrNull()?.also { iconCache.put(cacheKey, it) }

        OverlayAppPresentation(
            target = app.toVolumeTarget(),
            icon = icon,
            volume = app.volume,
            isPlaying = app.isPlaying,
            controlState = app.controlState
        )
    }

    fun loadShizukuIcon(): Bitmap? = runCatching {
        packageManager.getApplicationIcon("moe.shizuku.privileged.api").toBitmap(68, 68)
    }.getOrNull()

    fun clear() {
        iconCache.evictAll()
    }
}
