package com.agentkosticka.amply.dnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.service.notification.Condition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.net.toUri
import androidx.core.content.edit

enum class DndOperationResult {
    APPLIED,
    ACCESS_REQUIRED,
    FEATURE_DISABLED,
    FAILED
}

/** Controls only Amply's own DND rule; it never disables a user or schedule-owned rule. */
class AmplyDndController(context: Context) {
    companion object {
        internal const val STORAGE_NAME = "amply_dnd_state"
        private const val RULE_ID_KEY = "automatic_rule_id"
        internal const val ACTIVE_KEY = "active"
        internal val CONDITION_ID: Uri = "condition://com.agentkosticka.amply/manual_dnd".toUri()
    }

    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val storage = appContext.getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE)
    private val _active = MutableStateFlow(storage.getBoolean(ACTIVE_KEY, false))
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    private var featureEnabled = false

    fun setFeatureEnabled(enabled: Boolean) {
        featureEnabled = enabled
        if (!enabled && _active.value) setActive(false)
    }

    fun hasPolicyAccess(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    fun canUseVolumeKeyStep(): Boolean = featureEnabled && hasPolicyAccess()

    fun requestPolicyAccess() {
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun toggleFromOverlay(): DndOperationResult {
        if (!featureEnabled) return DndOperationResult.FEATURE_DISABLED
        if (!hasPolicyAccess()) {
            requestPolicyAccess()
            return DndOperationResult.ACCESS_REQUIRED
        }
        return setActive(!_active.value)
    }

    @Synchronized
    fun setActive(active: Boolean): DndOperationResult {
        if (active && !featureEnabled) return DndOperationResult.FEATURE_DISABLED
        if (!hasPolicyAccess()) return DndOperationResult.ACCESS_REQUIRED
        return runCatching {
            val ruleId = ensureRuleId()
            val previousActive = _active.value
            storage.edit { putBoolean(ACTIVE_KEY, active) }
            val condition = Condition(
                CONDITION_ID,
                if (active) "Amply Do Not Disturb is on" else "Amply Do Not Disturb is off",
                if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
            )
            runCatching { notificationManager.setAutomaticZenRuleState(ruleId, condition) }
                .getOrElse {
                    storage.edit(commit = true) { putBoolean(ACTIVE_KEY, previousActive)}
                    throw it
                }
            _active.value = active
            DndOperationResult.APPLIED
        }.getOrElse { DndOperationResult.FAILED }
    }

    @Suppress("DEPRECATION")
    private fun ensureRuleId(): String {
        storage.getString(RULE_ID_KEY, null)?.let { storedId ->
            if (runCatching { notificationManager.getAutomaticZenRule(storedId) }.getOrNull() != null) {
                return storedId
            }
        }
        val rule = AutomaticZenRule(
            "Amply",
            ComponentName(appContext, AmplyDndConditionProvider::class.java),
            CONDITION_ID,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true
        )
        return notificationManager.addAutomaticZenRule(rule).also { id ->
            storage.edit {putString(RULE_ID_KEY, id)}
        }
    }
}
