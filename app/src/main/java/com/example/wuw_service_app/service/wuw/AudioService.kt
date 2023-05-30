package com.example.wuw_service_app.service.wuw

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder

class AudioService: Service() {
    private lateinit var recorder: Recorder

    override fun onCreate() {
        super.onCreate()
        recorder = Recorder()
    }

    override fun startService(service: Intent?): ComponentName? {
        val componentName = super.startService(service)
        recorder!!.startRecording()
        return componentName
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder!!.stopRecording()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // not required by the moment
        // returning null
        return null
    }
}