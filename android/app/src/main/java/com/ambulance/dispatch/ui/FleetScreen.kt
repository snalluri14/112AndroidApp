package com.ambulance.dispatch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ambulance.dispatch.BuildConfig
import com.ambulance.dispatch.data.Assignment
import com.ambulance.dispatch.data.UiState
import com.ambulance.dispatch.data.VehicleSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fleet list: every ambulance's status. Route details are never shown for other vehicles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetScreen(state: UiState, onOpenMap: () -> Unit, onChangeVehicle: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    val myId = state.vehicleId
    val fleet = remember(state.fleet, myId) { state.fleet.sortedWith(compareBy({ it.id != myId }, { it.id })) }
    val busyCount = state.fleet.count { it.busy }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ambulance $myId", fontWeight = FontWeight.Bold)
                        ConnectionLabel(state.connected)
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Change vehicle ID") }, onClick = { menuOpen = false; askPin = true })
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.connected) item { OfflineBanner(state.fleetUpdatedAt) }
            item { BatteryOptimisationCard() }
            state.assignment?.let { a -> item { ActiveDispatchCard(a, onOpenMap) } }
            item {
                Text(
                    "${state.fleet.size - busyCount} available · $busyCount busy",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(fleet, key = { it.id }) { v -> VehicleRow(v, isMine = v.id == myId) }
        }
    }

    if (askPin) PinDialog(onDismiss = { askPin = false }, onConfirmed = { askPin = false; onChangeVehicle() })
}

@Composable
fun ConnectionLabel(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) AvailableGreen else Color.Gray))
        Spacer(Modifier.width(6.dp))
        Text(
            if (connected) "Online" else "Offline – reconnecting…",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun OfflineBanner(updatedAt: Long?) {
    val time = updatedAt?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
        Text(
            "No connection to server. Showing last known status" + (time?.let { " (updated $it)" } ?: "") +
                ". New dispatches will arrive when the signal returns.",
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ActiveDispatchCard(a: Assignment, onOpenMap: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = BusyRed, contentColor = Color.White)) {
        Column(Modifier.padding(16.dp)) {
            Text("ACTIVE DISPATCH", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("Patient → ${a.toHospital.name ?: "Hospital"}")
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenMap,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = BusyRed),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open map", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun VehicleRow(v: VehicleSummary, isMine: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🚑  ${v.id}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (isMine) Text("This vehicle", style = MaterialTheme.typography.labelSmall)
            }
            StatusChip(v.busy)
        }
    }
}

@Composable
private fun StatusChip(busy: Boolean) {
    Text(
        if (busy) "BUSY" else "AVAILABLE",
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (busy) BusyRed else AvailableGreen)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Without this, some phones (Xiaomi, Vivo, Oppo…) kill the connection when the screen is off. */
@SuppressLint("BatteryLife")
@Composable
private fun BatteryOptimisationCard() {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(isIgnoringBatteryOptimisations(context)) }
    LifecycleResumeEffect(Unit) {
        ignoring = isIgnoringBatteryOptimisations(context)
        onPauseOrDispose { }
    }
    if (ignoring) return
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
        Column(Modifier.padding(12.dp)) {
            Text("Allow the app to run in the background", fontWeight = FontWeight.Bold)
            Text("Needed so dispatches arrive while the screen is off.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }) { Text("Allow") }
        }
    }
}

private fun isIgnoringBatteryOptimisations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@Composable
private fun PinDialog(onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change vehicle ID") },
        text = {
            Column {
                Text("Enter the admin PIN. The phone will stop receiving dispatches for this ambulance.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit); wrong = false },
                    label = { Text("PIN") },
                    singleLine = true,
                    isError = wrong,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (wrong) Text("Wrong PIN", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pin == BuildConfig.ADMIN_PIN) onConfirmed() else wrong = true }) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
