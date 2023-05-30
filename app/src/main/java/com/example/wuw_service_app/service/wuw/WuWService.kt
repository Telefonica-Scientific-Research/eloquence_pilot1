package com.example.wuw_service_app.service.wuw

import android.app.Service
import android.content.Intent
import android.os.IBinder

class WuWService: Service() {

    override fun onDestroy() {
        super.onDestroy()
        TODO("Is this needed?")
    }

    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
    }
}