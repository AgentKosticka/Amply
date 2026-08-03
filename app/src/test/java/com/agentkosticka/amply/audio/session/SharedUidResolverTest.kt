package com.agentkosticka.amply.audio.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedUidResolverTest {
    @Test fun singleCandidateIsAcceptedWithoutProcessData() {
        assertEquals("com.example.one", resolveSharedUidPackage(listOf("com.example.one"), null))
    }

    @Test fun processSuffixResolvesAnExactSharedUidPackage() {
        assertEquals(
            "com.example.two",
            resolveSharedUidPackage(
                listOf("com.example.one", "com.example.two"),
                "com.example.two:player"
            )
        )
    }

    @Test fun ambiguousSharedUidFailsClosed() {
        assertNull(resolveSharedUidPackage(listOf("com.example.one", "com.example.two"), null))
        assertNull(
            resolveSharedUidPackage(
                listOf("com.example.one", "com.example.two"),
                "com.example.unknown"
            )
        )
    }
}
