package com.ambulance.dispatch.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.ambulance.dispatch.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class UiState(
    val loaded: Boolean = false,
    val serverUrl: String = BuildConfig.SERVER_URL,
    val vehicleId: Int? = null,
    val connected: Boolean = false,
    val fleet: List<VehicleSummary> = emptyList(),
    val fleetUpdatedAt: Long? = null,
    val assignment: Assignment? = null,
    val backToBasePending: Boolean = false,
    val backToBaseMessage: String? = null,
)

sealed interface DispatchEvent {
    data class NewAssignment(val assignment: Assignment) : DispatchEvent
}

/**
 * Single source of truth for the app. Holds the live WebSocket to our server, keeps the
 * last known state on the phone, and reconnects automatically after losing signal.
 */
class DispatchRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val wsClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // detects dead connections (e.g. entering a no-signal area)
        .build()
    private val api = Api(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<DispatchEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    @Volatile private var token: String? = null
    private val lock = Any()
    private var running = false
    private var socket: WebSocket? = null
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var backToBaseJob: Job? = null

    init {
        scope.launch {
            val saved = prefs.load()
            token = saved.token
            _state.update {
                it.copy(
                    loaded = true,
                    serverUrl = saved.serverUrl ?: BuildConfig.SERVER_URL,
                    vehicleId = saved.vehicleId,
                    assignment = saved.assignmentJson?.let { j -> runCatching { AppJson.decodeFromString<Assignment>(j) }.getOrNull() },
                    fleet = saved.fleetJson?.let { j -> runCatching { AppJson.decodeFromString<List<VehicleSummary>>(j) }.getOrNull() }.orEmpty(),
                    fleetUpdatedAt = saved.fleetUpdatedAt,
                    backToBasePending = saved.backToBasePending,
                )
            }
            if (saved.backToBasePending) startBackToBaseLoop() // resume a tap made before the app was closed
        }
        // Reconnect immediately when the phone gets signal back, instead of waiting for the backoff timer.
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (running && !_state.value.connected) reconnectNow()
            }
        })
    }

    // ---------------- registration ----------------

    /** Returns null on success, or an error message for the setup screen. */
    suspend fun register(rawUrl: String, vehicleId: Int): String? {
        val url = normalizeUrl(rawUrl)
        return try {
            val out = api.register(url, vehicleId)
            prefs.saveRegistration(url, vehicleId, out.token)
            token = out.token
            _state.update { it.copy(serverUrl = url, vehicleId = vehicleId, assignment = null, fleet = emptyList()) }
            null
        } catch (e: ApiException) {
            if (e.code == 404) "Vehicle ID $vehicleId is not in the allowed fleet list on the server."
            else "Server error (${e.code}). Try again."
        } catch (e: IOException) {
            "Cannot reach the server at $url. Check the address and internet connection."
        }
    }

    suspend fun changeVehicle() {
        stop()
        backToBaseJob?.cancel()
        val s = _state.value
        val id = s.vehicleId
        val tok = token
        if (id != null && tok != null) {
            // Best effort: if offline, the server just keeps an extra phone entry for this ambulance.
            try { api.unregister(s.serverUrl, id, tok) } catch (e: IOException) { Log.i(TAG, "Unregister failed: ${e.message}") }
        }
        prefs.clearVehicle()
        token = null
        _state.update { UiState(loaded = true, serverUrl = it.serverUrl) }
    }

    /** Server lost our token (e.g. its state file was reset): silently register again with the saved ID. */
    private suspend fun reRegister(): Boolean {
        val s = _state.value
        val id = s.vehicleId ?: return false
        return try {
            val out = api.register(s.serverUrl, id)
            prefs.saveToken(out.token)
            token = out.token
            true
        } catch (e: IOException) {
            false
        }
    }

    // ---------------- live connection ----------------

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        scope.launch {
            state.first { it.loaded }
            connect()
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            reconnectJob?.cancel()
            socket?.close(1000, null)
            socket = null
        }
        _state.update { it.copy(connected = false) }
    }

    private fun reconnectNow() {
        synchronized(lock) {
            attempt = 0
            reconnectJob?.cancel()
            socket?.cancel()
            socket = null
        }
        connect()
    }

    private fun connect() {
        val s = _state.value
        val id = s.vehicleId ?: return
        val tok = token ?: return
        val wsUrl = "ws" + s.serverUrl.removePrefix("http") + "/ws?ambulance_id=$id&token=$tok" // http->ws, https->wss
        synchronized(lock) {
            if (!running || socket != null) return
            socket = wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), Listener())
        }
    }

    private fun scheduleReconnect(ws: WebSocket, reRegisterFirst: Boolean) {
        synchronized(lock) {
            if (socket !== ws) return // an old socket; a newer one is already active
            socket = null
            if (!running) return
            val delayMs = min(30_000L, 1_000L shl min(attempt, 5)) // 1s, 2s, 4s ... max 30s
            attempt++
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                if (reRegisterFirst) reRegister()
                delay(delayMs)
                connect()
            }
        }
        _state.update { it.copy(connected = false) }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) { attempt = 0 }
            _state.update { it.copy(connected = true) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(AppJson.parseToJsonElement(text).jsonObject)
            } catch (e: Exception) {
                Log.w(TAG, "Bad message from server: ${text.take(200)}", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect(webSocket, reRegisterFirst = code == 4403)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.i(TAG, "Connection lost: ${t.message}")
            scheduleReconnect(webSocket, reRegisterFirst = response?.code == 403)
        }
    }

    private fun handleMessage(msg: JsonObject) {
        when (msg["type"]?.jsonPrimitive?.content) {
            "snapshot" -> {
                setFleet(AppJson.decodeFromJsonElement(msg["vehicles"]!!))
                val a = msg["assignment"]?.takeUnless { it is JsonNull }?.let { AppJson.decodeFromJsonElement<Assignment>(it) }
                setAssignment(a)
            }
            "status" -> {
                val v = AppJson.decodeFromJsonElement<VehicleSummary>(msg["vehicle"]!!)
                val fleet = _state.value.fleet
                setFleet(if (fleet.any { it.id == v.id }) fleet.map { if (it.id == v.id) v else it } else fleet + v)
            }
            "fleet" -> setFleet(AppJson.decodeFromJsonElement(msg["vehicles"]!!)) // ambulance added/removed
            "assignment" -> setAssignment(AppJson.decodeFromJsonElement(msg["assignment"]!!))
            "assignment_cleared" -> setAssignment(null)
        }
    }

    private fun setFleet(fleet: List<VehicleSummary>) {
        val now = System.currentTimeMillis()
        _state.update { it.copy(fleet = fleet, fleetUpdatedAt = now) }
        scope.launch { prefs.saveFleet(AppJson.encodeToString(fleet), now) }
    }

    private fun setAssignment(a: Assignment?) {
        val previous = _state.value.assignment
        _state.update {
            if (a == null) it.copy(assignment = null, backToBasePending = false, backToBaseMessage = null)
            else it.copy(assignment = a)
        }
        scope.launch {
            prefs.saveAssignment(a?.let { AppJson.encodeToString(it) })
            if (a == null) prefs.setBackToBasePending(false)
        }
        if (a == null) backToBaseJob?.cancel()
        if (a != null && a.assignedAt != previous?.assignedAt) _events.tryEmit(DispatchEvent.NewAssignment(a))
    }

    // ---------------- back to base ----------------

    /** Driver tapped Back to Base. Keeps retrying (e.g. no signal) until the server confirms. */
    fun backToBase() {
        if (_state.value.assignment == null) return
        _state.update { it.copy(backToBasePending = true, backToBaseMessage = "Sending…") }
        scope.launch { prefs.setBackToBasePending(true) }
        startBackToBaseLoop()
    }

    private fun startBackToBaseLoop() {
        if (backToBaseJob?.isActive == true) return
        backToBaseJob = scope.launch {
            while (isActive) {
                val s = _state.value
                val id = s.vehicleId ?: break
                val message = try {
                    api.backToBase(s.serverUrl, id, token.orEmpty())
                    setAssignment(null) // confirmed: vehicle is AVAILABLE
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    when (e.code) {
                        403 -> { reRegister(); "Re-connecting to server…" }
                        502 -> "Dispatcher did not confirm yet. Retrying…"
                        else -> "Server error (${e.code}). Retrying…"
                    }
                } catch (e: IOException) {
                    "No network. Will send automatically when signal returns…"
                }
                _state.update { it.copy(backToBaseMessage = message) }
                delay(10_000)
            }
        }
    }

    companion object {
        private const val TAG = "DispatchRepo"

        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        }
    }
}
