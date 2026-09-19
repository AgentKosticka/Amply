package com.agentkosticka.amply.settings.ui

import android.content.pm.LauncherApps
import android.os.UserHandle
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
import kotlin.jvm.java

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
    uid: Int,
    bitmapSizePx: Int
): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val cacheKey = "$uid:$packageName@$bitmapSizePx"
    val cached = remember(cacheKey) { SettingsAppIconCache.get(cacheKey) }

    return produceState(initialValue = cached, cacheKey) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val launcherApps =
                        context.getSystemService(LauncherApps::class.java)
                    val userHandle = UserHandle.getUserHandleForUid(uid)
                    val density = context.resources.displayMetrics.densityDpi

                    val launcherIcon = launcherApps
                        .getActivityList(packageName, userHandle)
                        .firstOrNull()
                        ?.getIcon(density)

                    (launcherIcon
                        ?: context.packageManager.getApplicationIcon(packageName))
                        .toBitmap(bitmapSizePx, bitmapSizePx)
                        .asImageBitmap()
                }.getOrNull()?.also {
                    SettingsAppIconCache.put(cacheKey, it)
                }
            }
        }
    }.value
}
