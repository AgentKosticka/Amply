package com.agentkosticka.amply.tutorial

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TutorialCoordinatorTest {
    @Test
    fun existingUserWithoutStoredStageDefaultsToCompleted() {
        assertEquals(TutorialStage.COMPLETED, TutorialStage.fromStored(null, introductionSeen = true))
        assertEquals(TutorialStage.NOT_STARTED, TutorialStage.fromStored(null, introductionSeen = false))
        assertEquals(
            TutorialStage.COMPLETED,
            TutorialStage.fromStored("future-or-corrupt-stage", introductionSeen = true)
        )
    }

    @Test
    fun overlayHandoffRequiresVisibleTryItScreenAndIsConsumedOnce() = runTest {
        val store = FakeTutorialStore(TutorialStage.COMPLETED)
        val coordinator = TutorialCoordinator(store, this)

        // Exercise the initialization race deliberately: arm before the stored load runs.
        coordinator.arm()
        advanceUntilIdle()
        assertEquals(TutorialStage.WAITING_FOR_VOLUME_KEY, coordinator.stage.value)
        assertFalse(coordinator.onOverlayAttached())

        coordinator.setTryItScreenVisible(true)
        assertTrue(coordinator.onOverlayAttached())
        assertFalse(coordinator.onOverlayAttached())
        assertEquals(TutorialStage.WAITING_FOR_VOLUME_KEY, coordinator.stage.value)
        coordinator.onOverlayPreviewFinished()
        assertEquals(TutorialStage.OVERLAY_LEVEL, coordinator.stage.value)
    }

    @Test
    fun overlayAndAppStagesAdvanceInOrderAndCompleteAtomically() = runTest {
        val store = FakeTutorialStore(TutorialStage.NOT_STARTED)
        val coordinator = TutorialCoordinator(store, this)
        coordinator.goTo(TutorialStage.OVERLAY_LEVEL)

        TutorialStage.overlayStages.drop(1).forEach { expected ->
            coordinator.advanceOverlay()
            assertEquals(expected, coordinator.stage.value)
        }
        coordinator.advanceOverlay()
        assertEquals(TutorialStage.APP_ACCESS, coordinator.stage.value)
        TutorialStage.appStages.drop(1).forEach { expected ->
            coordinator.advanceAppTour()
            assertEquals(expected, coordinator.stage.value)
        }
        coordinator.advanceAppTour()
        advanceUntilIdle()

        assertEquals(TutorialStage.COMPLETED, coordinator.stage.value)
        assertTrue(store.setupCompleted)
        assertEquals(TutorialStage.COMPLETED, store.savedStage)
    }

    @Test
    fun skipCompletesSetupWithoutChangingExportedSettingsState() = runTest {
        val store = FakeTutorialStore(TutorialStage.WAITING_FOR_VOLUME_KEY)
        val coordinator = TutorialCoordinator(store, this)
        advanceUntilIdle()
        coordinator.skip()
        advanceUntilIdle()

        assertTrue(store.setupCompleted)
        assertEquals(TutorialStage.COMPLETED, store.savedStage)
    }

    private class FakeTutorialStore(initialStage: TutorialStage) : TutorialStore {
        var savedStage = initialStage
        var setupCompleted = false

        override suspend fun load(): TutorialStage = savedStage

        override suspend fun save(stage: TutorialStage) {
            savedStage = stage
        }

        override suspend fun completeSetupAndSave(stage: TutorialStage) {
            setupCompleted = true
            savedStage = stage
        }
    }
}
