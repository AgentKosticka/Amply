package com.agentkosticka.amply.settings.ui

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object SettingsAppIconCache {
    private val cache = LruCache<String, ImageBitmap>(96)

    @Synchronized
    fun get(key: String): ImageBitmap? = cache.get(key)

    @Synchronized
    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }
}

@Composable
internal fun rememberApplicationIconBitmap(
    packageName: String,
    bitmapSizePx: Int
): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val cacheKey = "$packageName@$bitmapSizePx"
    val cached = remember(cacheKey) { SettingsAppIconCache.get(cacheKey) }
    return produceState(initialValue = cached, cacheKey) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap(bitmapSizePx, bitmapSizePx)
                        .asImageBitmap()
                }.getOrNull()?.also { SettingsAppIconCache.put(cacheKey, it) }
            }
        }
    }.value
}
