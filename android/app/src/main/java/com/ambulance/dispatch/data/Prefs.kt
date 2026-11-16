package com.ambulance.dispatch.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "dispatch")

/** Everything the app keeps on the phone (survives app restarts and phone reboots). */
class Prefs(private val context: Context) {

    data class Saved(
        val serverUrl: String?,
        val vehicleId: Int?,
        val token: String?,
        val assignmentJson: String?,
        val fleetJson: String?,
        val fleetUpdatedAt: Long?,
        val backToBasePending: Boolean,
    )

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val VEHICLE_ID = intPreferencesKey("vehicle_id")
        val TOKEN = stringPreferencesKey("token")
        val ASSIGNMENT = stringPreferencesKey("assignment_json")
        val FLEET = stringPreferencesKey("fleet_json")
        val FLEET_UPDATED = longPreferencesKey("fleet_updated_at")
        val BTB_PENDING = booleanPreferencesKey("back_to_base_pending")
    }

    suspend fun load(): Saved {
        val p = context.dataStore.data.first()
        return Saved(
            serverUrl = p[Keys.SERVER_URL],
            vehicleId = p[Keys.VEHICLE_ID],
            token = p[Keys.TOKEN],
            assignmentJson = p[Keys.ASSIGNMENT],
            fleetJson = p[Keys.FLEET],
            fleetUpdatedAt = p[Keys.FLEET_UPDATED],
            backToBasePending = p[Keys.BTB_PENDING] ?: false,
        )
    }

    suspend fun saveRegistration(serverUrl: String, vehicleId: Int, token: String) = context.dataStore.edit {
        it[Keys.SERVER_URL] = serverUrl
        it[Keys.VEHICLE_ID] = vehicleId
        it[Keys.TOKEN] = token
    }

    suspend fun saveToken(token: String) = context.dataStore.edit { it[Keys.TOKEN] = token }

    suspend fun saveAssignment(json: String?) = context.dataStore.edit { it.setOrRemove(Keys.ASSIGNMENT, json) }

    suspend fun saveFleet(json: String, updatedAt: Long) = context.dataStore.edit {
        it[Keys.FLEET] = json
        it[Keys.FLEET_UPDATED] = updatedAt
    }

    suspend fun setBackToBasePending(pending: Boolean) = context.dataStore.edit { it[Keys.BTB_PENDING] = pending }

    /** "Change vehicle": forget the vehicle but keep the server address. */
    suspend fun clearVehicle() = context.dataStore.edit {
        it.remove(Keys.VEHICLE_ID)
        it.remove(Keys.TOKEN)
        it.remove(Keys.ASSIGNMENT)
        it.remove(Keys.BTB_PENDING)
    }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }
}
