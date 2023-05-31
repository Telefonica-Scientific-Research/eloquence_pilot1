package com.example.wuw_service_app.service.wuw

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.wuw_service_app.service.wuw.models.AudioModelsPipe
import com.example.wuw_service_app.service.wuw.models.WuWModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class AudioService: Service() {
    private var logTag = "AudioService"
    private lateinit var recorder: Recorder
    private lateinit var audioModelsPipe: AudioModelsPipe
    private lateinit var predictor: Predictor

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "AudioService onCreate")
        recorder = Recorder()
        audioModelsPipe = AudioModelsPipe(listOf(
            assetFilePath(this, "sgru.ptl")?.let { WuWModel(it) }
        ))
        predictor = Predictor(audioModelsPipe)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(logTag, "AudioService startService")
        recorder!!.startRecording()
        predictor.startPredicting()
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