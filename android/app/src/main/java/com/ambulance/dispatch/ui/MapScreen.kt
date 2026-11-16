package com.ambulance.dispatch.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ambulance.dispatch.data.Assignment
import com.ambulance.dispatch.data.UiState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Shown only on the dispatched ambulance's phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(state: UiState, assignment: Assignment, onBack: () -> Unit, onBackToBase: () -> Unit) {
    val context = LocalContext.current

    // Keep the screen on while the route is displayed.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Location permission is only for the blue "you are here" dot.
    var hasLocation by remember { mutableStateOf(hasLocationPermission(context)) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = hasLocationPermission(context)
    }
    LaunchedEffect(Unit) {
        if (!hasLocation) locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    val toPatient = remember(assignment) { assignment.toPatient.polyline.map { LatLng(it[0], it[1]) } }
    val toHospital = remember(assignment) { assignment.toHospital.polyline.map { LatLng(it[0], it[1]) } }
    val start = remember(assignment) { LatLng(assignment.start.lat, assignment.start.lon) }
    val patient = remember(assignment) { assignment.toPatient.destination.let { LatLng(it.lat, it.lon) } }
    val hospital = remember(assignment) { assignment.toHospital.destination.let { LatLng(it.lat, it.lon) } }
    val hospitalName = assignment.toHospital.name ?: "Hospital"

    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(start, 13f) }
    var mapLoaded by remember { mutableStateOf(false) }
    var fitRequest by remember { mutableIntStateOf(0) }

    // Zoom out to the whole trip when it opens. This also loads the map background along the
    // entire route while there is still signal, which helps in no-signal areas later.
    LaunchedEffect(mapLoaded, assignment.assignedAt, fitRequest) {
        if (!mapLoaded) delay(2_000) // offline, onMapLoaded may never fire; try anyway
        val bounds = LatLngBounds.builder().apply {
            (toPatient + toHospital + listOf(start, patient, hospital)).forEach { include(it) }
        }.build()
        runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
    }

    var confirmBackToBase by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Dispatch · Ambulance ${assignment.ambulanceId}", fontWeight = FontWeight.Bold)
                        ConnectionLabel(state.connected)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to list") }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegRow(RoutePatient, "To patient", routeKm(toPatient))
                    LegRow(RouteHospital, "To $hospitalName", routeKm(toHospital))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openNavigation(context, patient) }, modifier = Modifier.weight(1f)) {
                            Text("Navigate to patient")
                        }
                        OutlinedButton(onClick = { openNavigation(context, hospital) }, modifier = Modifier.weight(1f)) {
                            Text("Navigate to hospital")
                        }
                    }
                    if (state.backToBasePending) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(state.backToBaseMessage ?: "Sending…", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { confirmBackToBase = true },
                        enabled = !state.backToBasePending,
                        colors = ButtonDefaults.buttonColors(containerColor = AvailableGreen),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text(
                            if (state.backToBasePending) "Back to Base – waiting for confirmation" else "Back to Base",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                properties = MapProperties(isMyLocationEnabled = hasLocation),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = true,
                    myLocationButtonEnabled = hasLocation,
                    mapToolbarEnabled = false,
                ),
                onMapLoaded = { mapLoaded = true },
            ) {
                Polyline(points = toHospital, color = RouteHospital, width = 14f)
                Polyline(points = toPatient, color = RoutePatient, width = 14f)
                Marker(
                    state = remember(start) { MarkerState(position = start) },
                    title = "Ambulance start",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                )
                Marker(
                    state = remember(patient) { MarkerState(position = patient) },
                    title = "Patient",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                )
                Marker(
                    state = remember(hospital) { MarkerState(position = hospital) },
                    title = hospitalName,
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
                )
            }
            FilledTonalButton(
                onClick = { fitRequest++ },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) { Text("Show whole route") }
        }
    }

    if (confirmBackToBase) {
        AlertDialog(
            onDismissRequest = { confirmBackToBase = false },
            title = { Text("Back to base?") },
            text = { Text("This marks ambulance ${assignment.ambulanceId} as AVAILABLE and closes this dispatch.") },
            confirmButton = {
                TextButton(onClick = { confirmBackToBase = false; onBackToBase() }) { Text("Yes, back to base") }
            },
            dismissButton = { TextButton(onClick = { confirmBackToBase = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LegRow(color: Color, label: String, km: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 18.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(String.format(Locale.US, "%.1f km", km), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Opens turn-by-turn navigation in the Google Maps app (works offline if the area is downloaded there). */
private fun openNavigation(context: Context, to: LatLng) {
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1&destination=${to.latitude},${to.longitude}&travelmode=driving&dir_action=navigate"
    )
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri)) // no Google Maps app: open in browser
    }
}

/** Route length along the polyline (the dispatcher doesn't send distances). */
private fun routeKm(points: List<LatLng>): Double = points.zipWithNext { a, b ->
    val r = 6371.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
    2 * r * atan2(sqrt(h), sqrt(1 - h))
}.sum()
