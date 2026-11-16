package com.ambulance.dispatch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts listening for dispatches again after the phone restarts (the service exits if not set up). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) DispatchService.start(context)
    }
}
