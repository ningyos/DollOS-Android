package org.dollos.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File

class DollOSService : Service() {

    companion object {
        private const val TAG = "DollOSService"
        const val VERSION = "0.1.0"
        const val DATA_DIR = "/data/dollos"
    }

    private val binder = DollOSServiceImpl()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DollOS Service starting, version $VERSION")
        initDataDirectories()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun initDataDirectories() {
        val dirs = listOf(
            "$DATA_DIR/ai",
            "$DATA_DIR/avatar",
            "$DATA_DIR/voice",
            "$DATA_DIR/config"
        )
        for (path in dirs) {
            val dir = File(path)
            if (!dir.exists()) {
                val created = dir.mkdirs()
                Log.i(TAG, "Created directory $path: $created")
            }
        }
    }
}
