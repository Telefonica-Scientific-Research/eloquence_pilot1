package com.example.wuw_service_app

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.File
import com.example.wuw_service_app.service.wuw.AudioConstants
import com.example.wuw_service_app.service.wuw.WavFileWriter
import android.content.Intent
import kotlin.math.sqrt
import java.util.ArrayDeque
import android.os.Handler
import android.os.Looper

/**
 * Records conversation audio in WAV chunks and queues them for
 * WhisperX transcription and vector-store ingestion.
 *
 * Processes one queued chunk at a time, with up to three retries
 * after a failed attempt.
 */
class ConversationRecorder {

    private var logTag = "ConversationRecorder"

    // Flag to control the recording loop across threads.
    // @Volatile ensures that changes made in the Main Thread are immediately visible to the Recording Thread.
    @Volatile
    private var shouldContinueRecording = false


    // Background thread where the actual mic reading happens.
    private var recordingThread: Thread? = null

    // In-memory buffer to store audio samples before saving to a file.
    private val recordedData = mutableListOf<Short>()

    // References to Android context and user-defined session settings.
    private var currentContext: Context? = null
    private var currentIndexName: String = "" // Name of the Vector Store index.
    private var currentChunkDurationSeconds: Int = 30 // How often to split audio into chunks.

    private data class PendingAudioChunk(
        val context: Context,
        val audioFile: File,
        val indexName: String,
        val language: String,
        val chunkStartSeconds: Double,
        var retryCount: Int = 0
    )

    private companion object {
        const val MAX_UPLOAD_RETRIES = 3
    }

    private val pendingChunks = ArrayDeque<PendingAudioChunk>()
    private var uploadInProgress = false
    // Advances only after successful ingestion; determines append mode.
    private var nextIngestChunkIndex = 0
    // Advances for every saved audio chunk, including silence or failed uploads.
    private var audioChunkIndex = 0

    // Hardware interface for capturing audio.
    private var audioRecord: AudioRecord? = null

    private var lastAudioLevelBroadcastTime = 0L
    private var smoothedAudioLevel = 0f

    @Volatile
    private var cleanupInProgress = false

    /**
     * Starts a recording session if the previous recording, cleanup,
     * and upload queue have finished.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Synchronized // only one thread at a time can call this to avoid starting multiple recorders.
    fun startRecording(
        context: Context,
        indexName: String,
        durationSeconds: Int
    ) {
        // Prevent starting multiple recording threads at once.
        if (recordingThread != null || cleanupInProgress) {
            Log.w(
                logTag,
                "[Recording Flow] Cannot start: previous recording is still stopping."
            )

            context.sendBroadcast(
                Intent("com.example.wuw.STATUS_UPDATE").putExtra(
                    "STATUS_MESSAGE",
                    "Wait for the previous recording to finish processing."
                )
            )
            return
        }

        val previousUploadStillRunning = synchronized(pendingChunks) {
            uploadInProgress || pendingChunks.isNotEmpty()
        }
        if (previousUploadStillRunning) {
            Log.e(
                logTag,
                "[Recording Flow] Cannot start: previous chunks are still being processed."
            )
            context.sendBroadcast(
                Intent("com.example.wuw.STATUS_UPDATE").putExtra(
                    "STATUS_MESSAGE",
                    "Please wait until the previous conversation finishes processing."
                )
            )
            return
        }

        Log.d(logTag, "[Recording Flow] startRecording: Index=$indexName, Duration=$durationSeconds")

        // Save session parameters provided by the user.
        this.currentContext = context
        this.currentIndexName = indexName
        this.currentChunkDurationSeconds = durationSeconds
        synchronized(pendingChunks) {
            nextIngestChunkIndex = 0
            audioChunkIndex = 0
        }

        shouldContinueRecording = true
        recordedData.clear()

        lastAudioLevelBroadcastTime = 0L
        smoothedAudioLevel = 0f

        // This creates a background thread to run your record() function, giving that thread a descriptive name.
        recordingThread = Thread {
            try {
                record()
            } catch (e: SecurityException) {
                Log.e(logTag, "[Recording Flow] SecurityException: Permission not granted or revoked: ${e.message}")
                shouldContinueRecording = false
            }
        }.apply { name = "ConversationRecorderThread" }
        // This starts (runs) the thread.
        recordingThread!!.start() // !! is the non-null assertion.
    }

    /**
     * Requests recording to stop and starts background cleanup.
     * Queues any remaining audio without waiting for uploads to finish.
     */
    @Synchronized // only one thread at a time.
    fun stopRecording(context: Context) { // takes an Android Context to save the last chunk.
        Log.d(logTag, "[Recording Flow] stopRecording called for $currentIndexName")

        if (recordingThread == null) {
            Log.w(logTag, "[Recording Flow] stopRecording: No recording thread found.")
            return
        }

        if (cleanupInProgress) {
            Log.w(
                logTag,
                "[Recording Flow] stopRecording: cleanup already in progress."
            )
            return
        }

        cleanupInProgress = true

        // Signal the loop in the background thread to stop.
        shouldContinueRecording = false
        sendAudioLevel(context, 0f)

        // Immediate hardware stop to unblock the .read() method if it's waiting for audio.
        try {
            audioRecord?.stop()
            Log.d(logTag, "[Recording Flow] AudioRecord hardware stopped.")
        } catch (e: Exception) {
            Log.e(logTag, "[Recording Flow] Error stopping AudioRecord: ${e.message}")
        }

        val threadToJoin = recordingThread
        val nameToUse = currentIndexName

        // Run cleanup in a separate thread so the Main UI thread isn't blocked by file saving.
        // Finish stopping the microphone and queue the final partial chunk.
        Thread {
            try {
                Log.d(
                    logTag,
                    "[Recording Flow] Waiting for recording thread to finish..."
                )

                // Wait at most three seconds before continuing cleanup.
                threadToJoin?.join(3000)

                try {
                    audioRecord?.release()
                } catch (e: Exception) {
                    Log.e(
                        logTag,
                        "[Recording Flow] Error releasing AudioRecord: ${e.message}"
                    )
                }

                audioRecord = null

                // Save and queue whatever audio remains after the last complete chunk.
                Log.d(
                    logTag,
                    "[Recording Flow] Processing final chunk..."
                )

                saveChunk(
                    context = context,
                    indexName = nameToUse,
                    isFinal = true
                )

                Log.d(
                    logTag,
                    "[Recording Flow] Final chunk saved and queued."
                )
            } catch (e: Exception) {
                Log.e(
                    logTag,
                    "[Recording Flow] Error during recording cleanup: ${e.message}",
                    e
                )
            } finally {
                // This runs whether saving succeeded or failed.
                synchronized(this@ConversationRecorder) {
                    recordingThread = null
                    currentContext = null
                    cleanupInProgress = false
                }

                Log.d(
                    logTag,
                    "[Recording Flow] Recording cleanup finished."
                )
            }
        }.apply {
            name = "ConversationRecorderCleanupThread"
            start()
        }

        Log.d(logTag, "[Recording Flow] Stop signal sent. Cleanup continues in background.")
    }

    /**
     * Captures microphone audio and saves chunks when the configured
     * sample threshold is reached.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun record() {
        Log.d(logTag, "[Recording Flow] Thread started. Initializing AudioRecord...")

        // 1. Get the sample rate (e.g., 16000Hz).
        // This is how many "snapshots" of sound are taken per second.
        val sampleRate = AudioConstants.SAMPLE_RATE

        // 2. Query Android for the minimum buffer size needed for these settings on this specific hardware.
        // This ensures the buffer is large enough for the system to pass audio data smoothly.
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioConstants.CHANNELS,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // 3. Create a temporary 'ShortArray' buffer.
        // This is a local "bucket" used to pull data from the mic hardware in each loop iteration.
        val audioBuffer = ShortArray(bufferSize)

        // 4. Initialize the AudioRecord object.
        // synchronized(this) ensures no other method (like stopRecording) touches the pointer while we're setting it up.
        synchronized(this) {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,        // Use the phone's microphone.
                sampleRate,                           // The frequency (samples/sec).
                AudioConstants.CHANNELS,              // Mono or Stereo.
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize                            // Internal system buffer size.
            )
        }

        // 5. Verify the recorder is ready.
        // It can fail if permissions are missing or another app is using the mic exclusively.
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(logTag, "[Recording Flow] AudioRecord FAILED to initialize. Mic might be locked.")
            shouldContinueRecording = false
            return
        }

        try {
            // 6. Transition the hardware to the active Recording state.
            audioRecord?.startRecording()
            Log.d(logTag, "[Recording Flow] Mic recording started. Entering loop...")
        } catch (e: Exception) {
            Log.e(logTag, "[Recording Flow] Failed to start hardware recording: ${e.message}")
            shouldContinueRecording = false
            return
        }

        // 7. Calculate how many total samples equal the user-defined chunk duration (e.g. 16k * 10s = 160,000 samples).
        val samplesPerChunk = sampleRate * currentChunkDurationSeconds
        var samplesInCurrentChunk = 0

        // 8. THE MAIN LOOP: Runs continuously while 'shouldContinueRecording' is true.
        while (shouldContinueRecording) {
            // 9. read() is a "BLOCKING" call.
            // The thread pauses here and waits until the hardware has enough sound to fill 'audioBuffer'.
            // audioBuffer is destination bucket, read() function will pour the numbers (sound samples) it gets from the microphone into this array, bufferSize is the limit on how much to read (pour)
            // Blocking" Behavior: if the microphone hasn't finished "hearing" enough sound to fill the request yet,
            // your background thread simply sits there and hangs on that line. As soon as the hardware has the data,
            // the line "unblocks," the data is poured into audioBuffer, and the code moves to the next line.
            val read = audioRecord?.read(audioBuffer, 0, bufferSize) ?: -1
            // the return value "read" doesn't return the sound itself (the sound is now inside audioBuffer), it returns a number of samples read or 0 (still working) or negative number (error)

            if (read > 0) {
                calculateAndSendAudioLevel(currentContext, audioBuffer, read)
                // 10. Extract the actual samples from the hardware buffer.
                // take(read) ignores any trailing empty space in the array if the read was partial.
                val dataToTake = audioBuffer.take(read)

                // 11. THREAD SAFETY: Lock 'recordedData' while we append the new sound samples.
                // This prevents saveChunk() from trying to clear the list at the same time.
                synchronized(recordedData) {
                    recordedData.addAll(dataToTake) // recordedData is MutableListOf<Short>() --> Each Short = one audio sample (PCM data) = 16-bit per sample
                }
                samplesInCurrentChunk += read

                // 12. CHUNKING LOGIC: If we've reached the currentChunkDurationSeconds mark (e.g., 10 seconds)
                if (samplesInCurrentChunk >= samplesPerChunk) {
                    Log.d(logTag, "[Recording Flow] Chunk duration reached ($currentChunkDurationSeconds s). Processing...")

                    // 13. Call saveChunk to write the collected audio to a file and trigger the API upload.
                    // data moves from the live recording loop to the storage and upload process
                    // This happens on this thread, pausing recording for a few milliseconds while the file is created.
                    // saveChunk needs "it" (currentContext if exists, not null) to know where on the phone's disk it is allowed to save the .wav file (using context.cacheDir).
                    currentContext?.let { saveChunk(it, currentIndexName, isFinal = false) }
                    // .let { ... }: This is a scope function. If the context exists, it takes that context and gives it a temporary nickname: it.

                    // 14. Reset the sample counter for the next 10-second block.
                    samplesInCurrentChunk = 0
                }
            } else if (read < 0) {
                // 15. A negative value indicates a hardware error (like the mic being disconnected).
                if (shouldContinueRecording) {
                    Log.e(logTag, "[Recording Flow] Error reading from AudioRecord: $read")
                }
                break // Exit the loop on hardware failure.
            }
        }

        Log.d(logTag, "[Recording Flow] Loop exited. shouldContinueRecording=$shouldContinueRecording")

        // 16. TERMINATION CLEANUP: Shut down the hardware and release resources back to the Android OS.
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(logTag, "[Recording Flow] Error in thread cleanup: ${e.message}")
        }

        // 17. Safe pointer reset: Only clear the global audioRecord if we are the thread that was managing it.
        synchronized(this) {
            if (recordingThread == Thread.currentThread()) {
                audioRecord = null
            }
        }
        Log.d(logTag, "[Recording Flow] Thread finished.")
    }

    /**
     * Writes buffered audio to a temporary WAV file and queues it for processing.
     */
    private fun saveChunk(context: Context, indexName: String, isFinal: Boolean) {
        val dataToSave = synchronized(recordedData) {
            if (recordedData.isEmpty()) {
                Log.d(logTag, "[Recording Flow] saveChunk: No data to save. isFinal=$isFinal")
                return
            }
            val array = recordedData.toShortArray()
            recordedData.clear()
            array
        }

        val tempFile = File(context.cacheDir, "chunk_${System.currentTimeMillis()}.wav")
        try {
            WavFileWriter.writeWavFile(tempFile, dataToSave, AudioConstants.SAMPLE_RATE)
            val selectedLanguage = EloquenceAPI.currentLanguage

            val chunkStartSeconds = synchronized(pendingChunks) {
                val start = audioChunkIndex * currentChunkDurationSeconds.toDouble()
                audioChunkIndex++
                start
            }
            val pendingChunk = PendingAudioChunk(
                context = context.applicationContext,
                audioFile = tempFile,
                indexName = indexName,
                language = selectedLanguage,
                chunkStartSeconds = chunkStartSeconds
            )

            synchronized(pendingChunks) {
                pendingChunks.addLast(pendingChunk)
            }
            Log.d(
                logTag,
                "[Recording Flow] Queued audio chunk. " +
                        "Start=$chunkStartSeconds, Final=$isFinal"
            )
            processNextChunk()
        } catch (e: Exception) {
            Log.e(logTag, "[Recording Flow] Failed to save chunk: ${e.message}")
            tempFile.delete()
        }
    }

    private fun processNextChunk() {
        val next: Pair<PendingAudioChunk, Int> = synchronized(pendingChunks) {
            if (uploadInProgress || pendingChunks.isEmpty()) {
                return
            }

            uploadInProgress = true
            pendingChunks.first() to nextIngestChunkIndex
        }

        val pendingChunk = next.first
        val ingestChunkIndex = next.second
        val shouldAppend = ingestChunkIndex > 0

        Log.d(
            logTag,
            "[Recording Flow] Processing queued chunk. " +
                    "IngestChunk=$ingestChunkIndex, Append=$shouldAppend, " +
                    "Start=${pendingChunk.chunkStartSeconds}"
        )

        EloquenceAPI.transcribeAudioChunk(
            context = pendingChunk.context,
            audioFile = pendingChunk.audioFile,
            indexName = pendingChunk.indexName,
            language = pendingChunk.language,
            append = shouldAppend,
            chunkIndex = ingestChunkIndex,
            chunkStartSeconds = pendingChunk.chunkStartSeconds
        ) { result ->
            var continueWithNext = false
            var retryDelayMillis: Long? = null

            synchronized(pendingChunks) {
                uploadInProgress = false

                when (result) {
                    EloquenceAPI.ChunkUploadResult.INGESTED -> {
                        pendingChunks.removeFirst()
                        nextIngestChunkIndex++
                        continueWithNext = true
                    }

                    EloquenceAPI.ChunkUploadResult.NO_SPEECH -> {
                        pendingChunks.removeFirst()
                        continueWithNext = true
                    }

                    EloquenceAPI.ChunkUploadResult.FAILED -> {
                        if (pendingChunk.retryCount < MAX_UPLOAD_RETRIES) {
                            pendingChunk.retryCount++

                            retryDelayMillis =
                                1_000L * pendingChunk.retryCount

                            Log.w(
                                logTag,
                                "[Recording Flow] Chunk $ingestChunkIndex failed. " +
                                        "Retry ${pendingChunk.retryCount}/$MAX_UPLOAD_RETRIES"
                            )
                        } else {
                            Log.e(
                                logTag,
                                "[Recording Flow] Chunk $ingestChunkIndex failed permanently. " +
                                        "Skipping it and continuing."
                            )

                            pendingChunks.removeFirst()
                            pendingChunk.audioFile.delete()
                            continueWithNext = true
                        }
                    }
                }
            }

            val delay = retryDelayMillis

            if (delay != null) {
                Handler(Looper.getMainLooper()).postDelayed(
                    { processNextChunk() },
                    delay
                )
            } else if (continueWithNext) {
                processNextChunk()
            }
        }
    }

    private fun sendAudioLevel(context: Context?, level: Float) {
        context ?: return

        val intent = Intent("com.example.wuw.AUDIO_LEVEL")
        intent.putExtra("AUDIO_LEVEL", level.coerceIn(0f, 1f))
        intent.putExtra("AUDIO_LEVEL_SOURCE", "conversation")
        context.sendBroadcast(intent)
    }

    private fun calculateAndSendAudioLevel(
        context: Context?,
        buffer: ShortArray,
        read: Int
    ) {
        if (context == null || read <= 0) return

        val now = System.currentTimeMillis()

        // Around 30 FPS. Avoids spamming broadcasts.
        if (now - lastAudioLevelBroadcastTime < 33L) return

        var sum = 0.0
        for (i in 0 until read) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }

        val rms = sqrt(sum / read)

        // Microphone-dependent noise floor and display scaling.
        val normalized = ((rms - 80.0) / 1000.0)
            .coerceIn(0.06, 1.0)
            .toFloat()

        // Smooth level changes for the UI meter.
        smoothedAudioLevel = smoothedAudioLevel * 0.70f + normalized * 0.30f

        sendAudioLevel(context, smoothedAudioLevel)
        lastAudioLevelBroadcastTime = now
    }
}