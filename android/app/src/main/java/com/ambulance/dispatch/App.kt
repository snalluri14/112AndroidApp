package com.ambulance.dispatch

import android.app.Application
import android.content.Context
import com.ambulance.dispatch.data.DispatchRepository
import com.ambulance.dispatch.service.Notifications

class App : Application() {
    lateinit var repo: DispatchRepository
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        repo = DispatchRepository(this)
    }
}

val Context.repo: DispatchRepository get() = (applicationContext as App).repo
