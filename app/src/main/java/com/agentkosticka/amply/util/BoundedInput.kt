package com.agentkosticka.amply.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(minOf(maxBytes.coerceAtLeast(1), 8 * 1024))
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}
