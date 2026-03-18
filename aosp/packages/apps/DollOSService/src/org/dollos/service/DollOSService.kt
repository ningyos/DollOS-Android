package org.dollos.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class DollOSService : Service() {

    companion object {
        private const val TAG = "DollOSService"
    }

    private val binder = DollOSServiceImpl()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DollOS Service bound")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}
