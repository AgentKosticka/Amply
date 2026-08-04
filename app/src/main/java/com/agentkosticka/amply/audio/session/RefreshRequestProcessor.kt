package com.agentkosticka.amply.audio.session

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Runs the first request immediately, then collapses a burst into one trailing refresh. */
internal suspend fun processRefreshRequests(
    requests: ReceiveChannel<Unit>,
    debounceMs: Long,
    refresh: suspend () -> Unit
) {
    for (ignored in requests) {
        refresh()
        delay(debounceMs.milliseconds)
        var trailingRefreshNeeded = false
        while (requests.tryReceive().isSuccess) {
            trailingRefreshNeeded = true
        }
        if (trailingRefreshNeeded) refresh()
    }
}
