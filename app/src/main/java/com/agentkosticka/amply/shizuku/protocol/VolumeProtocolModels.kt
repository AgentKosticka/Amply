package com.agentkosticka.amply.shizuku.protocol

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

const val VOLUME_PROTOCOL_VERSION = 6
const val CAPABILITY_TYPED_PLAYBACKS = 1L shl 0
const val CAPABILITY_VERIFIED_STREAM_VOLUME = 1L shl 1
const val CAPABILITY_PLAYBACK_QUERY_STATUS = 1L shl 2
const val VOLUME_PROTOCOL_CAPABILITIES =
    CAPABILITY_TYPED_PLAYBACKS or CAPABILITY_VERIFIED_STREAM_VOLUME or CAPABILITY_PLAYBACK_QUERY_STATUS

object VolumeOperationStatus {
    const val OK = 1
    const val FAILED = -1
    const val DENIED = -2
    const val UNSUPPORTED = -3
    const val UNAVAILABLE = -4
    const val INVALID_ARGUMENT = -5
    const val NOT_FOUND = -6
}

@Parcelize
data class PlaybackQueryResultParcel(
    val status: Int,
    val sessions: List<PlaybackSessionParcel> = emptyList()
) : Parcelable {
    fun requireSessions(): List<PlaybackSessionParcel> {
        check(status == VolumeOperationStatus.OK) { "Playback query unavailable" }
        check(sessions.all { it.isValid() }) { "Invalid playback query response" }
        return sessions
    }
}

@Parcelize
data class PlaybackSessionParcel(
    val userId: Int,
    val uid: Int,
    val pid: Int,
    val playerInterfaceId: Int,
    val playerState: Int,
    val streamType: Int,
    val usage: Int,
    val contentType: Int,
    val muted: Boolean,
    val volume: Float
) : Parcelable {
    fun isValid(): Boolean = userId >= 0 && uid > 0 && userId == uid / 100_000 && pid > 0 &&
        playerInterfaceId > 0 && playerState >= 0 && streamType in 0..11 && usage >= 0 &&
        contentType in 0..4 && volume.isFinite() && volume in 0f..1f
}

@Parcelize
data class OperationResultParcel(
    val status: Int,
    val verified: Boolean,
    val diagnosticCode: String
) : Parcelable {
    val succeeded: Boolean get() = status == VolumeOperationStatus.OK

    companion object {
        fun success(verified: Boolean = false): OperationResultParcel =
            OperationResultParcel(VolumeOperationStatus.OK, verified, "ok")

        fun failure(status: Int, code: String): OperationResultParcel =
            OperationResultParcel(status, false, code)
    }
}
