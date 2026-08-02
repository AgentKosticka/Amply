package com.agentkosticka.amply.audio

/** The media destination represented by the large action icon above the media bar. */
enum class MediaOutputRoute(val wireName: String) {
    LOCAL("MUSIC"),
    BLUETOOTH("BLUETOOTH"),
    CAST("CAST")
}

data class MediaRouteVolumeState(
    val outputRoute: MediaOutputRoute = MediaOutputRoute.LOCAL,
    val generation: Long = 0L,
    val currentVolume: Int = 0,
    val maxVolume: Int = 0,
    val variableVolume: Boolean = false
) {
    val isRemote: Boolean get() = outputRoute == MediaOutputRoute.CAST
    val isControllableRemote: Boolean get() = isRemote && variableVolume && maxVolume > 0
}

internal object MediaVolumeActionPolicy {
    fun resolve(
        automatic: VolumeKeyStreamAction,
        route: MediaRouteVolumeState
    ): VolumeKeyStreamAction {
        if (automatic !is VolumeKeyStreamAction.Adjust || automatic.target != VolumeTarget.MEDIA ||
            !route.isRemote
        ) return automatic
        return if (route.isControllableRemote) {
            VolumeKeyStreamAction.AdjustRemoteMedia(route.generation)
        } else {
            VolumeKeyStreamAction.PassThrough
        }
    }
}

internal enum class MediaRouteDeviceKind {
    LOCAL,
    BLUETOOTH,
    REMOTE
}

internal data class MediaRouteSnapshot(
    /** Non-empty only when Android can report the devices actually selected for media. */
    val routedDevices: Set<MediaRouteDeviceKind> = emptySet(),
    val selectedRouteDevice: MediaRouteDeviceKind = MediaRouteDeviceKind.LOCAL,
    val selectedRouteIsRemote: Boolean = false
)

internal object MediaOutputRoutePolicy {
    fun resolve(snapshot: MediaRouteSnapshot): MediaOutputRoute {
        // Android 13+ gives us the actual devices for USAGE_MEDIA. Prefer that answer.
        if (snapshot.routedDevices.isNotEmpty()) {
            return when {
                MediaRouteDeviceKind.REMOTE in snapshot.routedDevices -> MediaOutputRoute.CAST
                MediaRouteDeviceKind.BLUETOOTH in snapshot.routedDevices -> MediaOutputRoute.BLUETOOTH
                else -> MediaOutputRoute.LOCAL
            }
        }

        // Android 10-12: use the selected route, not every connected Bluetooth device.
        return when {
            snapshot.selectedRouteDevice == MediaRouteDeviceKind.BLUETOOTH -> {
                MediaOutputRoute.BLUETOOTH
            }
            snapshot.selectedRouteIsRemote ||
                snapshot.selectedRouteDevice == MediaRouteDeviceKind.REMOTE -> {
                MediaOutputRoute.CAST
            }
            else -> MediaOutputRoute.LOCAL
        }
    }
}
