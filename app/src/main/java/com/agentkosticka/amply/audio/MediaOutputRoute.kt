package com.agentkosticka.amply.audio

/** The media destination represented by the large action icon above the media bar. */
enum class MediaOutputRoute(val wireName: String) {
    LOCAL("MUSIC"),
    BLUETOOTH("BLUETOOTH"),
    CAST("CAST")
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
