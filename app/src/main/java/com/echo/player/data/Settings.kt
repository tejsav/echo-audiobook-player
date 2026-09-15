package com.echo.player.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

private val LEVEL_VOLUME = booleanPreferencesKey("level_volume")

/** App-wide switches. */
object Settings {

    fun levelVolume(context: Context): Flow<Boolean> =
        context.applicationContext.settingsStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[LEVEL_VOLUME] ?: false }

    suspend fun setLevelVolume(context: Context, on: Boolean) {
        context.applicationContext.settingsStore.edit { it[LEVEL_VOLUME] = on }
    }
}
