package com.agentkosticka.amply.audio.ringer

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamMuteToggleControllerTest {
    @Test
    fun `icon mute restores exact previous volume`() {
        val controller = StreamMuteToggleController()

        assertEquals(0, controller.nextVolume(STREAM, 9, 0, 15))
        assertEquals(9, controller.nextVolume(STREAM, 0, 0, 15))
    }

    @Test
    fun `minimum reached outside toggle restores only first tick`() {
        val controller = StreamMuteToggleController()
        controller.nextVolume(STREAM, 9, 0, 15)

        controller.onVolumeChangedOutsideToggle(STREAM)

        assertEquals(1, controller.nextVolume(STREAM, 0, 0, 15))
    }

    @Test
    fun `restore levels are independent per stream`() {
        val controller = StreamMuteToggleController()

        assertEquals(0, controller.nextVolume(1, 4, 0, 10))
        assertEquals(0, controller.nextVolume(2, 7, 0, 10))
        assertEquals(4, controller.nextVolume(1, 0, 0, 10))
        assertEquals(7, controller.nextVolume(2, 0, 0, 10))
    }

    @Test
    fun `nonzero stream minimum uses the tick above that minimum`() {
        val controller = StreamMuteToggleController()

        assertEquals(3, controller.nextVolume(STREAM, 2, 2, 5))
    }

    private companion object {
        const val STREAM = 3
    }
}
