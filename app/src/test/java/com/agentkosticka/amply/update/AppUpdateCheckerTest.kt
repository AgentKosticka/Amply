package com.agentkosticka.amply.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun semanticVersionsParseAndCompareNumerically() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("v1.2.3"))
        assertEquals(SemanticVersion(10, 0, 1), SemanticVersion.parse("10.0.1"))
        assertNull(SemanticVersion.parse("v1.2"))
        assertTrue(SemanticVersion(1, 10, 0) > SemanticVersion(1, 9, 99))
    }

    @Test
    fun offlineCheckDoesNotFetchOrRecordSuccess() = runTest {
        val store = FakeStore(lastSuccess = 0L)
        var fetchCount = 0
        val checker = checker(
            store = store,
            online = false,
            source = {
                fetchCount++
                SemanticVersion(2, 0, 0)
            }
        )

        checker.checkIfDue()

        assertEquals(0, fetchCount)
        assertEquals(0L, store.lastSuccess)
        assertNull(checker.availableUpdate.value)
    }

    @Test
    fun successfulCheckIsSuppressedForTwentyFourHours() = runTest {
        val now = 2_000_000_000L
        val store = FakeStore(lastSuccess = now - UPDATE_CHECK_INTERVAL_MS + 1L)
        var fetchCount = 0
        val checker = checker(store, now = now, source = {
            fetchCount++
            SemanticVersion(2, 0, 0)
        })

        checker.checkIfDue()

        assertEquals(0, fetchCount)
    }

    @Test
    fun dueSuccessfulCheckRecordsTimestampAndPublishesNewerRelease() = runTest {
        val now = 3_000_000_000L
        val store = FakeStore(lastSuccess = now - UPDATE_CHECK_INTERVAL_MS)
        val checker = checker(
            store = store,
            now = now,
            source = { SemanticVersion(1, 1, 0) }
        )

        checker.checkIfDue()

        assertEquals(now, store.lastSuccess)
        assertEquals(SemanticVersion(1, 1, 0), checker.availableUpdate.value?.version)
        assertEquals(
            "https://github.com/AgentKosticka/Amply/releases/tag/v1.1.0",
            checker.availableUpdate.value?.releaseUrl
        )
    }

    @Test
    fun failedRequestIsNotRecordedAndCurrentReleaseDoesNotAlert() = runTest {
        val failedStore = FakeStore(0L)
        checker(failedStore, source = { error("network failure") }).checkIfDue()
        assertEquals(0L, failedStore.lastSuccess)

        val currentStore = FakeStore(0L)
        val currentChecker = checker(
            currentStore,
            source = { SemanticVersion(1, 0, 1) }
        )
        currentChecker.checkIfDue()
        assertTrue(currentStore.lastSuccess > 0L)
        assertNull(currentChecker.availableUpdate.value)
    }

    private fun checker(
        store: FakeStore,
        online: Boolean = true,
        now: Long = 4_000_000_000L,
        source: suspend () -> SemanticVersion
    ) = AppUpdateChecker(
        store = store,
        onlineStatus = OnlineStatus { online },
        releaseSource = LatestReleaseSource { source() },
        currentVersion = { SemanticVersion(1, 0, 1) },
        nowEpochMs = { now }
    )

    private class FakeStore(var lastSuccess: Long) : UpdateCheckStore {
        override suspend fun lastSuccessfulCheckEpochMs(): Long = lastSuccess

        override suspend fun recordSuccessfulCheck(epochMs: Long) {
            lastSuccess = epochMs
        }
    }
}
