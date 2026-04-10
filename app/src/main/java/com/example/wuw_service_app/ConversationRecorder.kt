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

/**
 * Handles the "Record Conversation" feature.
 * This runs in parallel to the Wake Word detection.
 * It records audio in chunks, sends each chunk to WhisperX for transcription,
 * and then ingests the result into a specific Vector Store index.
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
    private var currentChunkDurationSeconds: Int = 10 // How often to split audio into chunks.
    private var isFirstChunk = true // Used to decide if we append or create a new index.

    // Hardware interface for capturing audio.
    private var audioRecord: AudioRecord? = null

    /**
     * Starts the background recording thread.
     * Redundancy fix: Configuration values are now read directly from EloquenceAPI at save-time.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Synchronized // only one thread at a time can call this to avoid starting multiple recorders.
    fun startRecording(
        context: Context, 
        indexName: String, 
        durationSeconds: Int
    ) {
        // Prevent starting multiple recording threads at once.
        if (recordingThread != null) {
            Log.w(logTag, "[Recording Flow] startRecording called but thread is already running.")
            return
        }

        Log.d(logTag, "[Recording Flow] startRecording: Index=$indexName, Duration=$durationSeconds")

        // Save session parameters provided by the user.
        this.currentContext = context
        this.currentIndexName = indexName
        this.currentChunkDurationSeconds = durationSeconds
        this.isFirstChunk = true // Reset for new session.
        
        shouldContinueRecording = true
        recordedData.clear()

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
     * Stops the recording thread and ensures the last bits of audio are processed.
     */
    @Synchronized // only one thread at a time.
    fun stopRecording(context: Context) { // takes an Android Context to save the last chunk.
        Log.d(logTag, "[Recording Flow] stopRecording called for $currentIndexName")

        if (recordingThread == null) {
            Log.w(logTag, "[Recording Flow] stopRecording: No recording thread found.")
            return
        }
        
        // Signal the loop in the background thread to stop.
        shouldContinueRecording = false
        
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
        Thread {
            try {
                // Wait for the recording loop to exit gracefully (up to 3 seconds).
                Log.d(logTag, "[Recording Flow] Waiting for recording thread to join in background...")
                threadToJoin?.join(3000)
            } catch (e: InterruptedException) {
                Log.e(logTag, "[Recording Flow] Error joining thread: ${e.message}")
            }
            
            // Release hardware resources.
            audioRecord?.release()
            audioRecord = null

            // Final Save: process whatever is left in the recordedData buffer.
            Log.d(logTag, "[Recording Flow] Processing final chunk in background...")
            saveChunk(context, nameToUse, isFinal = true)
            Log.d(logTag, "[Recording Flow] stopRecording cleanup finished in background.")
        }.start()
        
        // Reset state variables.
        recordingThread = null
        this.currentContext = null
        Log.d(logTag, "[Recording Flow] stopRecording: Signal sent. Main thread released.")
    }

    /**
     * The continuous audio capture loop (Runs on a background thread).
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
     * Saves the buffered audio to a WAV file and uploads it to the backend.
     */
    private fun saveChunk(context: Context, indexName: String, isFinal: Boolean) {
        // Step 1: Safely extract data from the list and clear it for the next chunk
        val dataToSave = synchronized(recordedData) { // recordedData is in-memory buffer (MutableList<Short>)
            if (recordedData.isEmpty()) {
                Log.d(logTag, "[Recording Flow] saveChunk: No data to save. isFinal=$isFinal")
                return
            }
            val array = recordedData.toShortArray() // Convert the list (MutableList<>) of Short values to an Array. Converting your collected audio samples into a more efficient format for processing or saving.
            recordedData.clear() // Empty the list so the recording thread can start fresh
            array // In Kotlin, the last expression inside a block is automatically returned
        } // So synchronized block returns array (val dataToSave = array)

        // Step 2: Create a temporary file in the app's cache directory with a unique timestamp
        val tempFile = File(context.cacheDir, "chunk_${System.currentTimeMillis()}.wav")
        try {
            // Step 3: Write the PCM data with a proper WAV header (Sample rate, size, etc.) in the cacheDir
            WavFileWriter.writeWavFile(tempFile, dataToSave, AudioConstants.SAMPLE_RATE)
            // (the whole audio pipeline is buffer → array → WAV file) (array decouples recording from saving)

            // Step 4: Determine if this is the start of a session or a continuation
            val shouldAppend = !isFirstChunk // if not the first chunk, append to existing index
            val selectedLanguage = EloquenceAPI.currentLanguage

            Log.d(logTag, "[Recording Flow] Sending chunk to WhisperX. Samples: ${dataToSave.size}, Append: $shouldAppend, Language: $selectedLanguage, Final: $isFinal")

            // Step 5: Upload the file to the Transcription/Diarization and Ingestion API (Eloquence backend)
            EloquenceAPI.transcribeAudioChunk(
                context = context, 
                audioFile = tempFile, 
                indexName = indexName, 
                language = selectedLanguage, 
                append = shouldAppend,
                /// chunkSize = currentChunkSize,
                /// embedName = currentEmbedName,
                /// splittingStrategy = currentSplittingStrategy,
                /// percentile = currentPercentile
            )
            
            isFirstChunk = false // Mark that the first piece has been processed for this session
        } catch (e: Exception) {
            Log.e(logTag, "[Recording Flow] Failed to save/send chunk: ${e.message}")
            tempFile.delete() // Clean up file on failure to avoid filling disk space
        }
    }
}
