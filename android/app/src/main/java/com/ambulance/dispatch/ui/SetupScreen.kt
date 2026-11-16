package com.ambulance.dispatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** First launch only: the driver enters the ambulance ID, which is then saved on the phone. */
@Composable
fun SetupScreen(initialServerUrl: String, onRegister: suspend (serverUrl: String, vehicleId: Int) -> String?) {
    var vehicleId by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf(initialServerUrl) }
    var showServer by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().imePadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🚑", style = MaterialTheme.typography.displayMedium)
            Text("Ambulance Dispatch", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter this ambulance's vehicle ID. It is saved on this phone, so you only do this once.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = vehicleId,
                onValueChange = { vehicleId = it.filter(Char::isDigit).take(9); error = null },
                label = { Text("Vehicle ID (e.g. 10022)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showServer) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; error = null },
                    label = { Text("Server address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(onClick = { showServer = true }) { Text("Server: $serverUrl  (change)") }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val id = vehicleId.toIntOrNull() ?: return@Button
                    scope.launch {
                        busy = true
                        error = onRegister(serverUrl, id)
                        busy = false
                    }
                },
                enabled = vehicleId.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("Save and continue")
            }
        }
    }
}
