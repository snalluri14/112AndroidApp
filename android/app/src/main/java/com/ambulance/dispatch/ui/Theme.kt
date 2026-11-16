package com.ambulance.dispatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BusyRed = Color(0xFFC62828)
val AvailableGreen = Color(0xFF2E7D32)
val RoutePatient = Color(0xFFD32F2F)
val RouteHospital = Color(0xFF1565C0)

@Composable
fun DispatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = BusyRed,
            onPrimary = Color.White,
            secondary = RouteHospital,
        ),
        content = content,
    )
}
