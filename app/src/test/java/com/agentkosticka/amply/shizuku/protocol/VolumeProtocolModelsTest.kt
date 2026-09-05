package com.agentkosticka.amply.shizuku.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VolumeProtocolModelsTest {
    @Test fun idleQueryIsSuccessfulButUnavailableQueryIsNot() {
        assertTrue(PlaybackQueryResultParcel(VolumeOperationStatus.OK).requireSessions().isEmpty())
        assertThrows(IllegalStateException::class.java) {
            PlaybackQueryResultParcel(VolumeOperationStatus.UNAVAILABLE).requireSessions()
        }
        assertThrows(IllegalStateException::class.java) {
            PlaybackQueryResultParcel(VolumeOperationStatus.FAILED).requireSessions()
        }
    }

    @Test fun queryRejectsMalformedSessionPayload() {
        assertThrows(IllegalStateException::class.java) {
            PlaybackQueryResultParcel(VolumeOperationStatus.OK, listOf(valid().copy(pid = 0))).requireSessions()
        }
    }
    private fun valid() = PlaybackSessionParcel(
        userId = 1,
        uid = 100_123,
        pid = 42,
        playerInterfaceId = 7,
        playerState = 2,
        streamType = 3,
        usage = 1,
        contentType = 2,
        muted = false,
        volume = 0.5f
    )

    @Test fun acceptsValidatedPlaybackMetadata() {
        assertTrue(valid().isValid())
    }

    @Test fun rejectsNonFiniteGainAndInvalidIdentity() {
        assertFalse(valid().copy(volume = Float.NaN).isValid())
        assertFalse(valid().copy(volume = Float.POSITIVE_INFINITY).isValid())
        assertFalse(valid().copy(userId = 0).isValid())
        assertFalse(valid().copy(playerInterfaceId = -1).isValid())
        assertFalse(valid().copy(pid = 0).isValid())
    }

    @Test fun operationSuccessRequiresOkStatus() {
        assertTrue(OperationResultParcel.success(verified = true).succeeded)
        assertFalse(
            OperationResultParcel.failure(VolumeOperationStatus.INVALID_ARGUMENT, "invalid_input")
                .succeeded
        )
    }
}
