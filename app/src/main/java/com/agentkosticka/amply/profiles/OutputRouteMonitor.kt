package com.agentkosticka.amply.profiles

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OutputRouteState(
    val descriptor: OutputDeviceDescriptor,
    val generation: Long = 0L,
    val remoteVolume: Int = 0,
    val remoteMaxVolume: Int = 0,
    val remoteVolumeVariable: Boolean = false
)

class OutputRouteMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mediaRouter = appContext.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var selectedRoute: MediaRouter.RouteInfo? = null
    private var started = false
    private var generation = 0L
    private var lastKey = ""

    private val _state = MutableStateFlow(defaultState())
    val state: StateFlow<OutputRouteState> = _state.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private val routeCallback = object : MediaRouter.SimpleCallback() {
        override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = refresh()
        override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
        override fun onRouteVolumeChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
    }

    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        mediaRouter.addCallback(
            MediaRouter.ROUTE_TYPE_LIVE_AUDIO,
            routeCallback,
            MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS
        )
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        mediaRouter.removeCallback(routeCallback)
    }

    fun refresh() {
        val route = runCatching {
            mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        }.getOrNull()
        selectedRoute = route
        val routed = routedDevices(route)
        val descriptor = describe(route, routed)
        if (descriptor.key != lastKey) {
            generation += 1L
            lastKey = descriptor.key
        }
        val remoteRoute = route?.takeIf { it.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE }
        _state.value = OutputRouteState(
            descriptor = descriptor,
            generation = generation,
            remoteVolume = remoteRoute?.volume ?: 0,
            remoteMaxVolume = remoteRoute?.volumeMax ?: 0,
            remoteVolumeVariable = remoteRoute?.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE
        )
    }

    fun setRemoteVolume(index: Int): Boolean {
        val route = selectedRoute ?: return false
        if (route.playbackType != MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE ||
            route.volumeHandling != MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE
        ) return false
        return runCatching {
            route.requestSetVolume(index.coerceIn(0, route.volumeMax))
            true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun routedDevices(route: MediaRouter.RouteInfo?): List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 33) {
        runCatching { audioManager.getAudioDevicesForAttributes(mediaAttributes) }.getOrDefault(emptyList())
    } else {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { device ->
            device.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                (device.type !in BLUETOOTH_TYPES || route?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH)
        }
    }

    private fun describe(
        route: MediaRouter.RouteInfo?,
        devices: List<AudioDeviceInfo>
    ): OutputDeviceDescriptor {
        val remote = route?.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
        if (remote) {
            val name = route.getName(appContext)?.toString()?.trim().orEmpty().ifBlank { "Casting" }
            val identity = listOf(name, route.description?.toString().orEmpty(), route.deviceType.toString())
                .joinToString("|")
            return descriptor(OutputKind.CAST, name, identity, OutputIdentityQuality.BEST_EFFORT)
        }
        val bluetooth = devices.filter { it.type in BLUETOOTH_TYPES }
        if (bluetooth.isNotEmpty() || route?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
            val addresses = bluetooth.map { it.address.trim() }.filter(String::isNotBlank).sorted()
            val routeName = route?.getName(appContext)?.toString()?.trim().orEmpty()
            val productName = bluetooth.firstNotNullOfOrNull { it.productName?.toString()?.takeIf(String::isNotBlank) }
            val pairedName = pairedBluetoothName(addresses.firstOrNull())
            val name = pairedName ?: routeName.ifBlank { productName ?: "Bluetooth audio" }
            val identity = addresses.takeIf(List<String>::isNotEmpty)?.joinToString("|")
                ?: listOf(name, bluetooth.map { it.type }.sorted().joinToString(",")).joinToString("|")
            val quality = if (addresses.isNotEmpty()) OutputIdentityQuality.STABLE else OutputIdentityQuality.BEST_EFFORT
            return descriptor(OutputKind.BLUETOOTH, name, identity, quality)
        }
        val wired = devices.filter { it.type in WIRED_TYPES }
        if (wired.isNotEmpty()) {
            val stableParts = wired.map { it.address.trim() }.filter(String::isNotBlank).sorted()
            val usb = wired.any { it.type in USB_TYPES }
            val name = wired.firstNotNullOfOrNull { it.productName?.toString()?.takeIf(String::isNotBlank) }
                ?: if (usb) "USB audio" else "Wired headphones"
            val identity = if (stableParts.isNotEmpty()) stableParts.joinToString("|")
                else if (usb) "$name|${wired.map { it.type }.sorted()}" else "wired"
            return descriptor(
                OutputKind.WIRED,
                name,
                identity,
                if (stableParts.isNotEmpty()) OutputIdentityQuality.STABLE else OutputIdentityQuality.CATEGORY
            )
        }
        return OutputDeviceDescriptor("speaker", OutputKind.SPEAKER, "Phone speaker", OutputIdentityQuality.CATEGORY)
    }

    @Suppress("DEPRECATION")
    private fun pairedBluetoothName(address: String?): String? {
        if (address.isNullOrBlank()) return null
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            appContext.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices
                ?.firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?.name
        }.getOrNull()
    }

    private fun descriptor(
        kind: OutputKind,
        name: String,
        rawIdentity: String,
        quality: OutputIdentityQuality
    ) = OutputDeviceDescriptor(
        key = "${kind.name.lowercase()}:${sha256(rawIdentity)}",
        kind = kind,
        displayName = name.take(80),
        identityQuality = quality
    )

    private fun defaultState() = OutputRouteState(
        OutputDeviceDescriptor("speaker", OutputKind.SPEAKER, "Phone speaker", OutputIdentityQuality.CATEGORY)
    )

    companion object {
        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST
        )
        private val USB_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
        private val WIRED_TYPES = USB_TYPES + setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC
        )

        internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }
}
