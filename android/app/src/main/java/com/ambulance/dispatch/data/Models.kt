package com.ambulance.dispatch.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON shapes sent by our FastAPI server (see server/app/models.py). */

@Serializable
data class LatLon(val lat: Double, val lon: Double)

@Serializable
data class Leg(
    val destination: LatLon,
    /** List of [lat, lon] pairs, exactly as the dispatcher sent them. */
    val polyline: List<List<Double>>,
    val name: String? = null,
)

@Serializable
data class Assignment(
    @SerialName("ambulance_id") val ambulanceId: Int,
    val start: LatLon,
    @SerialName("to_patient") val toPatient: Leg,
    @SerialName("to_hospital") val toHospital: Leg,
    @SerialName("assigned_at") val assignedAt: String,
)

@Serializable
data class VehicleSummary(
    val id: Int,
    val status: String,
    @SerialName("busy_since") val busySince: String? = null,
) {
    val busy: Boolean get() = status == "BUSY"
}

@Serializable
data class RegisterOut(
    @SerialName("ambulance_id") val ambulanceId: Int,
    val token: String,
)

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
