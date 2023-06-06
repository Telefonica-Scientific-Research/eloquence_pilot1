package com.example.wuw_service_app.service.wuw

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.voice.VoiceInteractionService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.wuw_service_app.MainActivity
import com.example.wuw_service_app.service.wuw.models.AudioModelsCallback
import com.example.wuw_service_app.service.wuw.models.AudioModelsPipe
import com.example.wuw_service_app.service.wuw.models.WuWModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class AudioService: VoiceInteractionService() {
    private var logTag = "AudioService"
    private lateinit var recorder: Recorder
    private lateinit var audioModelsPipe: AudioModelsPipe
    private lateinit var predictor: Predictor
    private val channelId = "AudioServiceChannel"

    // notification
    private var numUtterances: Int = 0
    private val audioManagerCallback = object : AudioModelsCallback {
        override fun invoke(keywordIndex: Int) {
            numUtterances++
            val contentText = if (numUtterances === 1) " time!" else " times!"
            val n = getNotification(
                "Wake word",
                "Detected $numUtterances$contentText"
            )
            val notificationManager =
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)!!
            notificationManager!!.notify(1234, n)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "AudioService onCreate")

        // audio
        recorder = Recorder()
        audioModelsPipe = AudioModelsPipe(listOf(
            assetFilePath(this, "sgru.ptl")?.let { WuWModel(it) }
        ), audioManagerCallback)
        predictor = Predictor(audioModelsPipe)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                channelId,
                "AudioService",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(
                NotificationManager::class.java
            )
            manager.createNotificationChannel(notificationChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(logTag, "AudioService startService")
        recorder!!.startRecording()
        predictor.startPredicting()

        createNotificationChannel()
        val notification = if ((recorder == null) or (predictor == null))  getNotification(
            "Audio Service init failed",
            "Service will be shut down"
        ) else getNotification("Wake word", "Service running")
        startForeground(1234, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(logTag, "AudioService onDestroy")
        predictor.stopPredicting()
        recorder!!.stopRecording()
    }

    // not used but needed
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun getNotification(title: String, message: String): Notification? {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_MUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.picture_frame)
            .setContentIntent(pendingIntent)
            .build()
    }

}

enum class AudioServiceStates {
    RECORDING_STARTED,
    MODELS_LOADED,
    WUW_DETECTED
}

private fun assetFilePath(context: Context, assetName: String): String? {
    val file = File(context.filesDir, assetName)
    if (file.exists() && file.length() > 0) {
        return file.absolutePath
    }
    try {
        context.assets.open(assetName).use { `is` ->
            FileOutputStream(file).use { os ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (`is`.read(buffer).also { read = it } != -1) {
                    os.write(buffer, 0, read)
                }
                os.flush()
            }
            return file.absolutePath
        }
    } catch (e: IOException) {
        Log.e("AssetsReader", assetName + ": " + e.localizedMessage)
    }
    return null
}