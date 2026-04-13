package com.example.wuw_service_app.service.wuw

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.wuw_service_app.MainActivity
import com.example.wuw_service_app.service.wuw.models.AudioModelsCallback
import com.example.wuw_service_app.service.wuw.models.AudioModelsPipe
import com.example.wuw_service_app.service.wuw.models.WuWModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Environment
import androidx.annotation.RequiresPermission
import com.example.wuw_service_app.EloquenceAPI
import com.example.wuw_service_app.ConversationRecorder
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.Service

import java.io.ByteArrayOutputStream

/**
 * Foreground Service that constantly listens for the Wake Word (e.g., "OK Aura").
 * When detected, it records the user query until silence and fetches an answer via RAG.
 */
class AudioService : Service() {
    private var logTag = "AudioService"
    // Components for the local AI wake word detection
    private lateinit var recorder: Recorder
    private lateinit var audioModelsPipe: AudioModelsPipe
    private lateinit var predictor: Predictor
    private val channelId = "AudioServiceChannel"

    // Component for long-form conversation recording
    private val conversationRecorder = ConversationRecorder()

    // CHECK IF THIS IS BEING USED
    // mirar tema notification. it is currently disabled
    companion object {
        // Using EloquenceAPI as the source of truth for configurations
        // Utility to check which knowledge base index is currently active
        fun getSelectedIndex(): String = EloquenceAPI.currentIndexName
    }

    private var numUtterances: Int = 0
    /**
     * This callback is triggered by the local Predictor when it hears the wake word.
     */
    private val audioManagerCallback = object : AudioModelsCallback {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun invoke(keywordIndex: Int) {
            numUtterances++
            Log.d(logTag, "[WakeWord] DETECTED! Triggering query recording...")

            // 1. Update the notification to show the user we detected the word
            val n = getNotification("Wake word", "Detected $numUtterances times!")
            val notificationManager = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            notificationManager.notify(1234, n)

            // 2. Inform the UI via broadcast
            sendStatusUpdate("Hi! Tell me. I'm listening...")

            // 3. Permission check before starting the high-quality recording of the user's question
            if (ContextCompat.checkSelfPermission(this@AudioService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                // Start recording the question immediately after detection
                recordQueryUntilSilence()
            } else {
                Log.e(logTag, "RECORD_AUDIO permission not granted")
            }
        }
    }

    /**
     * Sends a broadcast intent to the app. Any UI component (like MainActivity)
     * listening for "STATUS_UPDATE" will receive this text.
     */
    private fun sendStatusUpdate(message: String) {
        val intent = Intent("com.example.wuw.STATUS_UPDATE")
        intent.putExtra("STATUS_MESSAGE", message)
        sendBroadcast(intent)
    }

    /**
     * Records audio from the mic until it detects a period of silence.
     * This is used to capture the user's specific question/command.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun recordQueryUntilSilence(
        silenceThreshold: Int = 500, // Volume level below which we consider it "silent"
        silenceDurationMillis: Long = 2000 // Time (2 seconds) of silence before we stop recording
    ) {
        val sampleRate = AudioConstants.SAMPLE_RATE
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioConstants.CHANNELS, AudioFormat.ENCODING_PCM_16BIT)
        val audioBuffer = ShortArray(bufferSize)

        // Opening a dedicated AudioRecord instance for the query
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioConstants.CHANNELS,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // Safety check: if another part of the app (like ConversationRecorder) is using the mic, this might fail.
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(logTag, "[WakeWord] AudioRecord FAILED to initialize. Mic might be in use.")
            return
        }

        audioRecord.startRecording()
        Log.d(logTag, "[WakeWord] Recording query started. Threshold: $silenceThreshold")
        // sendStatusUpdate("Hi! I listen to your request...")

        val recordedData = mutableListOf<Short>()
        var lastSoundTime = System.currentTimeMillis()

        // CRITICAL: We run the recording loop in a separate thread so the Service (and UI) doesn't freeze.
        Thread {
            try {
                // Loop until silence is detected
                while (true) {
                    // 1. Pull raw audio from the microphone
                    val read = audioRecord.read(audioBuffer, 0, bufferSize)
                    // 2. If nothing was read (error or temporary lag), skip this iteration
                    if (read <= 0) continue // safety check that decides whether to process the data or skip to the next attempt.
                    // audioRecord.read() returns how many audio samples were read: positive = success, 0 = no data available at that moment, negative = error

                    val currentTime = System.currentTimeMillis()

                    // 3. CALCULATE VOLUME (RMS): Root Mean Square
                    // We square all samples, average them and take the square root to get the overall 'energy' or volume.
                    var sum = 0.0
                    for (i in 0 until read) {
                        val sample = audioBuffer[i].toDouble()
                        sum += sample * sample
                    }
                    val rms = Math.sqrt(sum / read)

                    // 4. Save the sound samples into our list to build the final question
                    recordedData.addAll(audioBuffer.take(read))

                    // 5. ACTIVITY DETECTION:
                    // If the volume (rms) is above the user-defined threshold, it means the user is still talking.
                    // We update 'lastSoundTime' to the current time, effectively resetting our "silence countdown".
                    if (rms > silenceThreshold) {
                        lastSoundTime = currentTime
                    }

                    // 6. SILENCE DETECTION:
                    // If the difference between now and the last time we heard a 'loud' sound is greater
                    // than our limit (e.g., 2000ms), it means the user has stopped talking.
                    if (currentTime - lastSoundTime > silenceDurationMillis) {
                        Log.d(logTag, "[WakeWord] Silence detected for ${silenceDurationMillis}ms. Stopping.")
                        break // Stop the loop
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "[WakeWord] Error during query recording: ${e.message}")
            } finally {
                // Always release hardware when done
                audioRecord.stop()
                audioRecord.release()
            }

            Log.d(logTag, "[WakeWord] Query recording finished. Total samples: ${recordedData.size}")
            sendStatusUpdate("Got it!")

            // 1. Convert raw audio numbers (PCM) into a WAV stream in memory
            val byteStream = ByteArrayOutputStream()
            WavFileWriter.writeWavToStream(byteStream, recordedData.toShortArray(), sampleRate)
            val audioBytes = byteStream.toByteArray()

            Log.d(logTag, "[WakeWord] Sending audio to Whisper...")
            sendStatusUpdate("Thinking...")

            // 2. Call Eloquence endpoint to transcribe the query
            EloquenceAPI.transcribeQueryWithWhisper(this@AudioService, audioBytes, language = EloquenceAPI.currentLanguage) { transcription ->
                if (!transcription.isNullOrEmpty()) {
                    Log.d(logTag, "[WakeWord] Transcription: '$transcription'. Context Index: ${EloquenceAPI.currentIndexName}")
                    
                    // 3. Call Eloquence endpoint to answer the question using RAG + LLM with the currently selected context
                    EloquenceAPI.getAnswerUsingRag(
                        this@AudioService,
                        transcription,
                        EloquenceAPI.currentIndexName, // Use the context from the singleton
                        "http://192.168.24.120:7997",
                        llmName = EloquenceAPI.currentLlmName,
                        docsK = EloquenceAPI.currentDocsK,
                        temp = EloquenceAPI.currentTemp,
                        topP = EloquenceAPI.currentTopP,
                        maxTokens = EloquenceAPI.currentMaxTokens,
                        language = EloquenceAPI.currentLanguage,
                        showOnScreen = true
                    )
                } else {
                    sendStatusUpdate("ERROR: Could not get an answer from the LLM+RAG")
                    Log.e(logTag, "[WakeWord] Transcription failed.")
                }
            }
        }.start()
    }

    /**
     * Called when the service is first created.
     * We initialize the local AI wake word components here.
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "AudioService onCreate: Initializing Wake Word Models")

        // Recorder handles the raw audio capture stream
        recorder = Recorder()
        // AudioModelsPipe connects the recorder to the AI model (sgru.ptl)
        audioModelsPipe = AudioModelsPipe(
            listOf(assetFilePath(this, "sgru.ptl")?.let { WuWModel(it) }), 
            audioManagerCallback
        )
        // Predictor runs the inference thread to detect the keyword in real-time
        predictor = Predictor(audioModelsPipe)
    }

    /**
     * Android 8.0+ requirement: Notifications must be assigned to a channel.
     * We set IMPORTANCE_HIGH so the service stays active and the user is aware.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(channelId, "AudioService", NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(notificationChannel)
        }
    }

    /**
     * Triggered whenever startService() or startForegroundService() is called.
     * This handles the actual start of the listening process and updates settings from the UI.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(logTag, "AudioService onStartCommand: action=${intent?.action}")

        // 1. REQUIRED: Start Foreground immediately to prevent the service from being killed
        createNotificationChannel()
        val notification = getNotification("Listening...", "Wake word & Recording active")
        startForeground(1234, notification)

        // 2. Handle configuration updates from the UI (passed via Intent)
        intent?.let {
            it.getStringExtra("SELECTED_INDEX")?.let { idx -> EloquenceAPI.currentIndexName = idx }
            EloquenceAPI.currentLlmName = it.getStringExtra("LLM_NAME") ?: EloquenceAPI.currentLlmName
            EloquenceAPI.currentDocsK = it.getIntExtra("DOCS_K", EloquenceAPI.currentDocsK)
            EloquenceAPI.currentTemp = it.getDoubleExtra("TEMP", EloquenceAPI.currentTemp)
            EloquenceAPI.currentTopP = it.getDoubleExtra("TOP_P", EloquenceAPI.currentTopP)
            EloquenceAPI.currentMaxTokens = it.getIntExtra("MAX_TOKENS", EloquenceAPI.currentMaxTokens)
            EloquenceAPI.currentLanguage = it.getStringExtra("LANGUAGE") ?: EloquenceAPI.currentLanguage
        }

        // 3. Handle Actions
        val action = intent?.action
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        when (action) {
            "ACTION_START_CONVERSATION" -> {
                val indexName = intent.getStringExtra("INDEX_NAME") ?: "default"
                val duration = intent.getIntExtra("DURATION", 10)

                if (hasPermission) {
                    conversationRecorder.startRecording(this, indexName, duration)
                } else {
                    Log.e(logTag, "Permission missing for ConversationRecorder")
                }
            }
            "ACTION_STOP_CONVERSATION" -> {
                conversationRecorder.stopRecording(this)
            }
            else -> {
                // Default behavior (Initial start): Wake Word detection
                if (hasPermission) {
                    recorder.startRecording()
                    predictor.startPredicting()
                }
            }
        }

        return START_STICKY
    }



    /**
     * Cleanup: Called when the service is stopped by the user or the system.
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.d(logTag, "AudioService onDestroy")
        predictor.stopPredicting()
        recorder.stopRecording()
    }

    /**
     * We use a "Started" service model, not a "Bound" one, so we return null.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Helper to create the notification shown in the system tray.
     * It includes a PendingIntent so clicking it opens the MainActivity.
     */
    private fun getNotification(title: String, message: String): Notification? {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_MUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .build()
    }
}

/**
 * Utility to find the physical file path of an asset.
 * Local PyTorch models often need a direct file path rather than an asset stream.
 * This function copies the model from the assets folder to the app's internal storage.
 */
private fun assetFilePath(context: Context, assetName: String): String? {
    val file = File(context.filesDir, assetName)
    if (file.exists() && file.length() > 0) return file.absolutePath
    try {
        context.assets.open(assetName).use { `is` ->
            FileOutputStream(file).use { os ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (`is`.read(buffer).also { read = it } != -1) os.write(buffer, 0, read)
                os.flush()
            }
            return file.absolutePath
        }
    } catch (e: IOException) {
        Log.e("AssetsReader", "$assetName: ${e.localizedMessage}")
    }
    return null
}
