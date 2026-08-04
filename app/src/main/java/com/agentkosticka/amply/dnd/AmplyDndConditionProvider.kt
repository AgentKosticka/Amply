@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.agentkosticka.amply.dnd

import android.net.Uri
import android.service.notification.Condition
import android.service.notification.ConditionProviderService

/** Owner component for Amply's manual Automatic Zen Rule. State is published by the controller. */
class AmplyDndConditionProvider : ConditionProviderService() {
    override fun onConnected() = publishStoredState()

    override fun onSubscribe(conditionId: Uri?) {
        if (conditionId == AmplyDndController.CONDITION_ID) publishStoredState()
    }

    override fun onUnsubscribe(conditionId: Uri?) = Unit

    private fun publishStoredState() {
        val active = getSharedPreferences(AmplyDndController.STORAGE_NAME, MODE_PRIVATE)
            .getBoolean(AmplyDndController.ACTIVE_KEY, false)
        notifyCondition(
            Condition(
                AmplyDndController.CONDITION_ID,
                if (active) "Amply Do Not Disturb is on" else "Amply Do Not Disturb is off",
                if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
            )
        )
    }
}
