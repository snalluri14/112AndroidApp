package com.ambulance.dispatch.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** HTTP error from our server (code = HTTP status). Network problems are plain IOExceptions. */
class ApiException(val code: Int, message: String) : IOException(message)

/** REST calls to our FastAPI server. */
class Api(private val client: OkHttpClient) {

    private val jsonType = "application/json".toMediaType()

    suspend fun register(baseUrl: String, vehicleId: Int): RegisterOut {
        val body = AppJson.encodeToString(buildJsonObject { put("ambulance_id", vehicleId) })
        val text = call(Request.Builder().url("$baseUrl/api/devices/register").post(body.toRequestBody(jsonType)).build())
        return AppJson.decodeFromString(text)
    }

    /** "Change vehicle": this phone stops being this ambulance (keeps the server's phone count accurate). */
    suspend fun unregister(baseUrl: String, vehicleId: Int, token: String) {
        call(
            Request.Builder()
                .url("$baseUrl/api/vehicles/$vehicleId/unregister")
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build()
        )
    }

    /** Driver tapped Back to Base. Server calls the dispatcher's endpoint and confirms. */
    suspend fun backToBase(baseUrl: String, vehicleId: Int, token: String) {
        call(
            Request.Builder()
                .url("$baseUrl/api/vehicles/$vehicleId/back-to-base")
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build()
        )
    }

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}: ${text.take(200)}")
            text
        }
    }
}
