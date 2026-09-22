package com.echo.player.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

private val LEVEL_VOLUME = booleanPreferencesKey("level_volume")
private val DRIVE_CATALOGS = stringSetPreferencesKey("drive_catalogs")
private val DRIVE_WIFI_ONLY = booleanPreferencesKey("drive_wifi_only")
private val LAST_RUN_VERSION = stringPreferencesKey("last_run_version")

/** App-wide switches. */
object Settings {

    fun levelVolume(context: Context): Flow<Boolean> =
        context.applicationContext.settingsStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[LEVEL_VOLUME] ?: false }

    suspend fun setLevelVolume(context: Context, on: Boolean) {
        context.applicationContext.settingsStore.edit { it[LEVEL_VOLUME] = on }
    }

    /** Links to the Drive catalogs this person has added. ECHO ships with none. */
    fun driveCatalogs(context: Context): Flow<Set<String>> =
        context.applicationContext.settingsStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[DRIVE_CATALOGS].orEmpty() }

    suspend fun addDriveCatalog(context: Context, link: String) {
        context.applicationContext.settingsStore.edit { it[DRIVE_CATALOGS] = it[DRIVE_CATALOGS].orEmpty() + link }
    }

    suspend fun removeDriveCatalog(context: Context, link: String) {
        context.applicationContext.settingsStore.edit { it[DRIVE_CATALOGS] = it[DRIVE_CATALOGS].orEmpty() - link }
    }

    /** Books are large, so downloads wait for Wi-Fi unless this is turned off. */
    fun driveWifiOnly(context: Context): Flow<Boolean> =
        context.applicationContext.settingsStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[DRIVE_WIFI_ONLY] ?: true }

    suspend fun setDriveWifiOnly(context: Context, on: Boolean) {
        context.applicationContext.settingsStore.edit { it[DRIVE_WIFI_ONLY] = on }
    }

    /** The version that last ran, so an update that happened quietly can be mentioned. */
    fun lastRunVersion(context: Context): Flow<String?> =
        context.applicationContext.settingsStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[LAST_RUN_VERSION] }

    suspend fun setLastRunVersion(context: Context, version: String) {
        context.applicationContext.settingsStore.edit { it[LAST_RUN_VERSION] = version }
    }
}
