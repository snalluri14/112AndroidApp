package com.ambulance.dispatch.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ambulance.dispatch.data.DispatchEvent
import com.ambulance.dispatch.repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service: keeps the connection to the server alive while the screen is off or the app
 * is in the background, and raises a loud alert when this ambulance is dispatched.
 */
class DispatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            Notifications.ONGOING_ID,
            Notifications.ongoing(this, "Connecting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        val repo = repo
        scope.launch {
            val s = repo.state.first { it.loaded }
            if (s.vehicleId == null) { stopSelf(); return@launch } // e.g. started at boot but never set up
            repo.start()
        }
        scope.launch {
            repo.events.collect { e ->
                if (e is DispatchEvent.NewAssignment) Notifications.dispatchAlert(this@DispatchService, e.assignment)
            }
        }
        scope.launch {
            repo.state
                .map { Triple(it.vehicleId, it.connected, it.assignment != null) }
                .distinctUntilChanged()
                .collect { (id, connected, busy) ->
                    if (id == null) return@collect
                    val conn = if (connected) "Online" else "Offline – reconnecting"
                    val job = if (busy) " · ON DISPATCH" else ""
                    Notifications.updateOngoing(this@DispatchService, "Ambulance $id · $conn$job")
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, DispatchService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, DispatchService::class.java))
        }
    }
}
