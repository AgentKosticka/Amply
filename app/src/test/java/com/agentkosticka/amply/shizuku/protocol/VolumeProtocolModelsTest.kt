package com.agentkosticka.amply.shizuku.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeProtocolModelsTest {
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

    @Test fun fractionalCapabilityIsOptionalDuringHandshake() {
        assertTrue(
            VOLUME_PROTOCOL_REQUIRED_CAPABILITIES and CAPABILITY_TYPED_PLAYBACKS != 0L
        )
        assertTrue(
            VOLUME_PROTOCOL_REQUIRED_CAPABILITIES and CAPABILITY_VERIFIED_STREAM_VOLUME != 0L
        )
        assertTrue(
            VOLUME_PROTOCOL_REQUIRED_CAPABILITIES and CAPABILITY_FLOAT_STREAM_VOLUME == 0L
        )
    }

    @Test fun fractionalStateRejectsUnavailableAndNonFiniteValues() {
        assertFalse(FractionalVolumeStateParcel.unavailable().available)
        assertTrue(FractionalVolumeStateParcel.cached(7.5f, 7).available)
        assertFalse(
            FractionalVolumeStateParcel(
                FractionalVolumeStatus.CACHED,
                Float.NaN,
                7
            ).available
        )
    }
}
