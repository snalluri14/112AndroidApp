package com.ambulance.dispatch.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambulance.dispatch.data.DispatchRepository
import com.ambulance.dispatch.repo
import com.ambulance.dispatch.service.DispatchService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DispatchTheme { AppRoot(repo) } }
    }
}

@Composable
private fun AppRoot(repo: DispatchRepository) {
    val state by repo.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Once a vehicle ID is saved: ask for notification permission and start listening for dispatches.
    LaunchedEffect(state.vehicleId) {
        if (state.vehicleId == null) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        DispatchService.start(context)
        repo.start()
    }

    when {
        !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        state.vehicleId == null -> SetupScreen(initialServerUrl = state.serverUrl, onRegister = repo::register)

        else -> {
            val assignment = state.assignment
            var showMap by rememberSaveable { mutableStateOf(assignment != null) }
            // A new dispatch opens the map automatically; Back to Base returns to the list.
            LaunchedEffect(assignment?.assignedAt) { showMap = assignment != null }

            if (showMap && assignment != null) {
                BackHandler { showMap = false }
                MapScreen(
                    state = state,
                    assignment = assignment,
                    onBack = { showMap = false },
                    onBackToBase = repo::backToBase,
                )
            } else {
                FleetScreen(
                    state = state,
                    onOpenMap = { showMap = true },
                    onChangeVehicle = {
                        DispatchService.stop(context)
                        scope.launch { repo.changeVehicle() }
                    },
                )
            }
        }
    }
}
