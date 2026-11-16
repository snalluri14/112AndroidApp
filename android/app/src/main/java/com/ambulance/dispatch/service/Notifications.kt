package com.ambulance.dispatch.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ambulance.dispatch.R
import com.ambulance.dispatch.data.Assignment
import com.ambulance.dispatch.ui.MainActivity

object Notifications {
    private const val CHANNEL_STATUS = "connection_status"
    private const val CHANNEL_ALERT = "dispatch_alert_v1"
    const val ONGOING_ID = 1
    private const val ALERT_ID = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that the app is listening for dispatches"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "New dispatch", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Loud alert when this ambulance is dispatched"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
            }
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ongoing(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_ambulance)
            .setContentTitle("Ambulance Dispatch")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(context))
            .build()

    fun updateOngoing(context: Context, text: String) {
        if (canNotify(context)) NotificationManagerCompat.from(context).notify(ONGOING_ID, ongoing(context, text))
    }

    fun dispatchAlert(context: Context, a: Assignment) {
        if (!canNotify(context)) return
        val hospital = a.toHospital.name ?: "hospital"
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_ambulance)
            .setContentTitle("🚑 NEW DISPATCH – Ambulance ${a.ambulanceId}")
            .setContentText("Go to patient, then $hospital. Tap to open the map.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .setFullScreenIntent(openAppIntent(context), true)
            .build()
        NotificationManagerCompat.from(context).notify(ALERT_ID, n)
    }

    private fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
