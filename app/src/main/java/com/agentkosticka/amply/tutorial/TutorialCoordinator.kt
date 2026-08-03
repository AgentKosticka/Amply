package com.agentkosticka.amply.tutorial

import com.agentkosticka.amply.settings.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal interface TutorialStore {
    suspend fun load(): TutorialStage
    suspend fun save(stage: TutorialStage)
    suspend fun completeSetupAndSave(stage: TutorialStage)
}

private class PreferencesTutorialStore(
    private val preferences: PreferencesManager
) : TutorialStore {
    override suspend fun load(): TutorialStage = preferences.tutorialStage.first()

    override suspend fun save(stage: TutorialStage) {
        preferences.setTutorialStage(stage)
    }

    override suspend fun completeSetupAndSave(stage: TutorialStage) {
        preferences.completeSetupAndSetTutorialStage(stage)
    }
}

internal class TutorialCoordinator(
    private val store: TutorialStore,
    private val scope: CoroutineScope
) {
    constructor(preferences: PreferencesManager, scope: CoroutineScope) :
        this(PreferencesTutorialStore(preferences), scope)

    private val _stage = MutableStateFlow(TutorialStage.NOT_STARTED)
    val stage: StateFlow<TutorialStage> = _stage.asStateFlow()

    private val tryItScreenVisible = AtomicBoolean(false)
    private val overlayHandoffConsumed = AtomicBoolean(false)
    private val locallyChanged = AtomicBoolean(false)
    private var lastWrite: Job? = null

    init {
        scope.launch {
            val restored = store.load()
            if (!locallyChanged.get()) _stage.value = restored
        }
    }

    fun setTryItScreenVisible(visible: Boolean) {
        tryItScreenVisible.set(visible)
    }

    fun arm() {
        overlayHandoffConsumed.set(false)
        updateStage(TutorialStage.WAITING_FOR_VOLUME_KEY)
    }

    fun replay() = arm()

    /** Returns true only when this overlay appearance should become the live tutorial preview. */
    fun onOverlayAttached(): Boolean {
        if (_stage.value != TutorialStage.WAITING_FOR_VOLUME_KEY || !tryItScreenVisible.get()) {
            return false
        }
        if (!overlayHandoffConsumed.compareAndSet(false, true)) return false
        return true
    }

    /** Called after the live accessibility overlay has had time to appear and animate away. */
    fun onOverlayPreviewFinished() {
        if (
            overlayHandoffConsumed.get() &&
            _stage.value == TutorialStage.WAITING_FOR_VOLUME_KEY
        ) {
            updateStage(TutorialStage.OVERLAY_LEVEL)
        }
    }

    fun goTo(stage: TutorialStage) {
        updateStage(stage)
    }

    fun advanceOverlay() {
        val index = TutorialStage.overlayStages.indexOf(_stage.value)
        if (index == -1) return
        if (index == TutorialStage.overlayStages.lastIndex) {
            enterAppTour()
        } else {
            updateStage(TutorialStage.overlayStages[index + 1])
        }
    }

    fun backOverlay() {
        val index = TutorialStage.overlayStages.indexOf(_stage.value)
        if (index > 0) updateStage(TutorialStage.overlayStages[index - 1])
    }

    fun enterAppTour() {
        locallyChanged.set(true)
        _stage.value = TutorialStage.APP_ACCESS
        enqueueWrite(TutorialWrite.SetupComplete(TutorialStage.APP_ACCESS))
    }

    fun advanceAppTour() {
        val index = TutorialStage.appStages.indexOf(_stage.value)
        if (index == -1) return
        if (index == TutorialStage.appStages.lastIndex) {
            complete()
        } else {
            updateStage(TutorialStage.appStages[index + 1])
        }
    }

    fun backAppTour() {
        val index = TutorialStage.appStages.indexOf(_stage.value)
        if (index > 0) updateStage(TutorialStage.appStages[index - 1])
    }

    fun complete() {
        tryItScreenVisible.set(false)
        locallyChanged.set(true)
        _stage.value = TutorialStage.COMPLETED
        enqueueWrite(TutorialWrite.SetupComplete(TutorialStage.COMPLETED))
    }

    fun skip() = complete()

    private fun updateStage(stage: TutorialStage) {
        locallyChanged.set(true)
        _stage.value = stage
        enqueueWrite(TutorialWrite.Stage(stage))
    }

    @Synchronized
    private fun enqueueWrite(write: TutorialWrite) {
        val previous = lastWrite
        lastWrite = scope.launch {
            previous?.join()
            when (write) {
                is TutorialWrite.Stage -> store.save(write.stage)
                is TutorialWrite.SetupComplete -> store.completeSetupAndSave(write.stage)
            }
        }
    }

    private sealed interface TutorialWrite {
        data class Stage(val stage: TutorialStage) : TutorialWrite
        data class SetupComplete(val stage: TutorialStage) : TutorialWrite
    }
}
