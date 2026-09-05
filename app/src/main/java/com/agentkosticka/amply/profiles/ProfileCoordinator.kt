package com.agentkosticka.amply.profiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.agentkosticka.amply.audio.ringer.NotificationAlertMode
import com.agentkosticka.amply.audio.ringer.RingerExperimentExecutor
import com.agentkosticka.amply.audio.routing.VolumeTarget
import com.agentkosticka.amply.audio.routing.VolumeBarModel
import com.agentkosticka.amply.dnd.AmplyDndController
import com.agentkosticka.amply.dnd.DndOperationResult
import com.agentkosticka.amply.settings.data.PreferencesManager
import com.agentkosticka.amply.settings.model.AppIdentity
import com.agentkosticka.amply.settings.model.VolumeDotScaleConfig
import com.agentkosticka.amply.shizuku.client.ShizukuVolumeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ProfileCoordinator(
    context: Context,
    private val preferences: PreferencesManager,
    val outputMonitor: OutputRouteMonitor,
    private val dndController: AmplyDndController,
    private val ringerExecutor: RingerExperimentExecutor,
    private val shizukuVolumeManager: ShizukuVolumeManager,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val controller = ProfileStateController(
        persistence = object : ProfilePersistence {
            override val profiles = preferences.profileStoreResolution
            override val apps = preferences.appSettings
            override val automaticSaving = preferences.automaticallySaveProfileChanges
            override suspend fun update(transform: (ProfileStore) -> ProfileStore) =
                preferences.updateProfileStore(transform)
        },
        output = outputMonitor.state,
        scope = scope,
        captureAudio = ::captureSnapshot,
        applyAudio = ::applySnapshot
    )
    val state = controller.state
    val effectiveAppVolumes = controller.effectiveAppVolumes

    private val systemObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = controller.audioChanged()
    }
    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = controller.audioChanged()
    }

    fun start() {
        outputMonitor.start()
        appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, systemObserver)
        ContextCompat.registerReceiver(appContext, ringerReceiver,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        controller.start()
        scope.launch { dndController.active.collect { controller.audioChanged() } }
    }

    suspend fun activateProfile(id: String) = controller.activateProfile(id)
    suspend fun createNamedProfile(name: String) = controller.createNamedProfile(name)
    suspend fun saveCurrentProfile() = controller.saveCurrentProfile()
    suspend fun updateProfile(id: String, name: String, snapshot: AudioProfileSnapshot) =
        controller.updateProfile(id, name, snapshot)
    suspend fun assignDevice(key: String, id: String?) = controller.assignDevice(key, id)
    suspend fun deleteProfile(id: String) = controller.deleteProfile(id)
    suspend fun forgetDevice(key: String) = controller.forgetDevice(key)
    fun volumeContext() = controller.volumeContext()
    suspend fun recordAppVolume(identity: AppIdentity, volume: Float, origin: ProfileVolumeContext) =
        controller.recordAppVolume(identity, volume, origin)

    fun onCallBecameActive() {
        scope.launch {
            controller.onCallBecameActive { applyLocalStream(VolumeTarget.CALL, it) }
        }
    }

    /** Live device limits used by the profile editor's shared Nothing-style rails. */
    fun systemVolumeBars(dotConfig: VolumeDotScaleConfig): Map<VolumeTarget, VolumeBarModel> {
        val route = outputMonitor.state.value
        val localReferenceMax = PROFILE_SYSTEM_TARGETS.maxOfOrNull { target ->
            runCatching { audioManager.getStreamMaxVolume(target.streamType) }.getOrDefault(1)
        }?.coerceAtLeast(1) ?: 16
        return PROFILE_SYSTEM_TARGETS.associateWith { target ->
            val remoteMedia = target == VolumeTarget.MEDIA &&
                route.descriptor.kind == OutputKind.CAST && route.remoteMaxVolume > 0
            val min = if (remoteMedia) 0 else {
                runCatching { audioManager.getStreamMinVolume(target.streamType) }.getOrDefault(0)
            }
            val max = if (remoteMedia) route.remoteMaxVolume else {
                runCatching { audioManager.getStreamMaxVolume(target.streamType) }.getOrDefault(min)
            }
            val reference = if (remoteMedia) max.coerceAtLeast(1) else localReferenceMax
            VolumeBarModel(
                target = target,
                aliases = setOf(target.streamType),
                label = target.label,
                currentVolume = if (remoteMedia) route.remoteVolume else {
                    runCatching { audioManager.getStreamVolume(target.streamType) }.getOrDefault(min)
                },
                minVolume = min,
                maxVolume = max,
                active = true,
                enabled = target.userAdjustable && (!remoteMedia || route.remoteVolumeVariable),
                referenceMaxVolume = reference,
                dotCount = dotConfig.resolvedDotCount(reference)
            )
        }
    }

    private suspend fun captureSnapshot(): AudioProfileSnapshot {
        val route = outputMonitor.state.value
        val streams = PROFILE_SYSTEM_TARGETS.associateWith { target ->
            if (target == VolumeTarget.MEDIA && route.descriptor.kind == OutputKind.CAST && route.remoteMaxVolume > 0) {
                route.remoteVolume.toFloat() / route.remoteMaxVolume
            } else {
                val min = runCatching { audioManager.getStreamMinVolume(target.streamType) }.getOrDefault(0)
                val max = runCatching { audioManager.getStreamMaxVolume(target.streamType) }.getOrDefault(min)
                val current = runCatching { audioManager.getStreamVolume(target.streamType) }.getOrDefault(min)
                normalizedVolume(current, min, max)
            }.coerceIn(0f, 1f)
        }
        return AudioProfileSnapshot(
            systemVolumes = streams,
            ringerMode = NotificationAlertMode.resolve(audioManager.ringerMode),
            dndEnabled = dndController.active.value.takeIf { dndController.hasPolicyAccess() }
        )
    }

    private suspend fun applySnapshot(snapshot: AudioProfileSnapshot): List<String> {
        val warnings = mutableListOf<String>()
        val topology = shizukuVolumeManager.getStreamTopology()
        val seenCanonical = mutableSetOf<Int>()
        val ringerPlan = profileRingerApplyPlan(snapshot.ringerMode)
        val localVolumesNeedingVerification = mutableListOf<Pair<VolumeTarget, Float>>()

        // Setting Loud can restore a remembered alert volume. Do it before applying
        // the saved Ring/Notifications levels so independently routed streams are
        // not collapsed back onto that remembered value.
        if (ringerPlan.applyBeforeVolumes) {
            ringerExecutor.setProductionAlertMode(snapshot.ringerMode, VolumeTarget.RING.streamType)
        }
        snapshot.systemVolumes.forEach { (target, fraction) ->
            val canonical = topology?.canonicalStream(target.streamType) ?: target.streamType
            if (!seenCanonical.add(canonical)) return@forEach
            val route = outputMonitor.state.value
            val applied = if (target == VolumeTarget.MEDIA && route.descriptor.kind == OutputKind.CAST) {
                if (route.remoteVolumeVariable && route.remoteMaxVolume > 0) {
                    outputMonitor.setRemoteVolume((fraction * route.remoteMaxVolume).toInt())
                } else false
            } else applyLocalStream(target, fraction)
            if (!applied && !(target == VolumeTarget.MEDIA && route.descriptor.kind == OutputKind.CAST)) {
                localVolumesNeedingVerification += target to fraction
            } else if (!applied) {
                warnings += "${target.label} volume could not be changed"
            }
        }

        // Alert-volume controls can promote the phone to Loud. Reassert non-Loud
        // modes after the levels are in place; unlike Loud, these modes do not
        // restore and overwrite an independently saved stream volume.
        if (ringerPlan.reapplyAfterVolumes) {
            ringerExecutor.setProductionAlertMode(snapshot.ringerMode, VolumeTarget.RING.streamType)
        }
        snapshot.dndEnabled?.let { enabled ->
            when (dndController.setActive(enabled)) {
                DndOperationResult.APPLIED -> Unit
                DndOperationResult.ACCESS_REQUIRED -> warnings += "Do Not Disturb access is required"
                else -> warnings += "Do Not Disturb could not be changed"
            }
        }
        warnings += settledAudioWarnings(
            expectedRingerMode = snapshot.ringerMode,
            requestedVolumes = localVolumesNeedingVerification
        )
        return warnings
    }

    private suspend fun settledAudioWarnings(
        expectedRingerMode: NotificationAlertMode,
        requestedVolumes: List<Pair<VolumeTarget, Float>>
    ): List<String> {
        var modeMatches = false
        var unresolvedVolumes = requestedVolumes
        repeat(6) {
            delay(100L)
            modeMatches = NotificationAlertMode.resolve(audioManager.ringerMode) == expectedRingerMode
            unresolvedVolumes = requestedVolumes.filter { (target, fraction) ->
                val min = runCatching { audioManager.getStreamMinVolume(target.streamType) }
                    .getOrDefault(0)
                val max = runCatching { audioManager.getStreamMaxVolume(target.streamType) }
                    .getOrDefault(min)
                val expected = volumeIndex(fraction, min, max)
                val observed = runCatching { audioManager.getStreamVolume(target.streamType) }
                    .getOrDefault(Int.MIN_VALUE)
                !profileVolumeSettled(
                    target = target,
                    expectedRingerMode = expectedRingerMode,
                    ringerModeMatches = modeMatches,
                    observedVolume = observed,
                    expectedVolume = expected
                )
            }
            if (modeMatches && unresolvedVolumes.isEmpty()) {
                return emptyList()
            }
        }
        return buildList {
            if (!modeMatches) add("Ringer mode could not be changed")
            unresolvedVolumes.forEach { (target, _) ->
                add("${target.label} volume could not be changed")
            }
        }
    }

    private fun applyLocalStream(target: VolumeTarget, fraction: Float): Boolean {
        val min = runCatching { audioManager.getStreamMinVolume(target.streamType) }.getOrDefault(0)
        val max = runCatching { audioManager.getStreamMaxVolume(target.streamType) }.getOrDefault(min)
        val index = volumeIndex(fraction, min, max)
        return if (target == VolumeTarget.RING || target == VolumeTarget.NOTIFICATION) {
            runCatching { ringerExecutor.setAlertVolumeFromControl(target.streamType, index) }.getOrDefault(false)
        } else runCatching {
            audioManager.setStreamVolume(target.streamType, index, 0)
            audioManager.getStreamVolume(target.streamType) == index
        }.getOrDefault(false)
    }

}
