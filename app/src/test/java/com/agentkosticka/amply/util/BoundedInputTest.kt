package com.agentkosticka.amply.util

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedInputTest {
    @Test fun readsUntilEofWhenBelowLimit() {
        val source = byteArrayOf(1, 2, 3)
        assertArrayEquals(source, ByteArrayInputStream(source).readAtMost(8))
    }

    @Test fun stopsExactlyAtLimit() {
        val result = ByteArrayInputStream(ByteArray(32) { it.toByte() }).readAtMost(7)
        assertEquals(7, result.size)
        assertArrayEquals(ByteArray(7) { it.toByte() }, result)
    }
}
