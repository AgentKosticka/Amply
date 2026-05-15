package com.agentkosticka.amply.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages app preferences using DataStore
 */
class PreferencesManager(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "amply_preferences")
        private val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        private val GAME_MODE_ENABLED = booleanPreferencesKey("game_mode_enabled")
    }

    /**
     * Flow that emits whether the setup wizard has been completed
     */
    val isSetupCompleted: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SETUP_COMPLETED] ?: false
        }

    /**
     * Marks the setup wizard as completed
     */
    suspend fun setSetupCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SETUP_COMPLETED] = completed
        }
    }

    /**
     * Flow that emits the game mode state
     */
    val isGameModeEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[GAME_MODE_ENABLED] ?: false
        }

    /**
     * Toggles game mode on/off
     */
    suspend fun setGameMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GAME_MODE_ENABLED] = enabled
        }
    }
}
