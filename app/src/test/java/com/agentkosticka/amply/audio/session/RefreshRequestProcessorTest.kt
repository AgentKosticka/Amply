package com.agentkosticka.amply.audio.session

import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshRequestProcessorTest {
    @Test
    fun firstRequestIsImmediateAndBurstGetsOneTrailingRefresh() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)
        var refreshCount = 0
        requests.trySend(Unit)
        val job = launch {
            processRefreshRequests(requests, debounceMs = 100L) { refreshCount++ }
        }

        runCurrent()
        assertEquals(1, refreshCount)

        requests.trySend(Unit)
        requests.trySend(Unit)
        runCurrent()
        assertEquals(1, refreshCount)

        advanceTimeBy(100L.milliseconds)
        runCurrent()
        assertEquals(2, refreshCount)
        job.cancel()
    }
}
