package com.example.wuw_service_app.service.wuw

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import prototypes.pep.mw_mh.Constants

import kotlin.math.max

class Recorder() {

    // Logging
    private var logTag = "Recorder"

    // Recording thread variables
    private var shouldContinueRecording = false
    private var recordingThread: Thread? = null

    init {
        initRecordingBuffer()
    }

    // Recording and Playback Methods
    @Synchronized
    fun startRecording() {
        Log.v(logTag, "Start recording")
        if (recordingThread != null) {
            return
        }
        shouldContinueRecording = true
        recordingThread = Thread { record() }
        recordingThread!!.start()
    }

    @Synchronized
    fun stopRecording() {
        Log.v(logTag, "Stop recording")
        if (recordingThread == null) {
            return
        }
        shouldContinueRecording = false
        recordingThread = null
    }

    @SuppressLint("MissingPermission") // Why is this needed?
    private fun record() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        // Estimate the buffer size we'll need for this device.
        var bufferSize = AudioRecord.getMinBufferSize(
            Constants.SAMPLE_RATE,
            Constants.CHANNELS,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = Constants.SAMPLE_RATE * 2
        }
        val audioBuffer = ShortArray(bufferSize / 2)

        Log.v(logTag, "Recording buffer size is $bufferSize.")

        val record = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            Constants.SAMPLE_RATE,
            Constants.CHANNELS,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(logTag, "Audio Record can't initialize!")
            return
        }
        try {
            record.startRecording()
            Log.v(logTag, "Start recording")
        } catch (e: Error) {
            Log.e(logTag, "Error record ${e.stackTrace}")
        }

        // Loop, gathering audio data and copying it to a round-robin buffer.
        while (shouldContinueRecording) {
            val numberRead = record.read(audioBuffer, 0, audioBuffer.size)
            val maxLength = AudioBuffer.recordingBuffer.size
            val newRecordingOffset = AudioBuffer.recordingOffset + numberRead
            val secondCopyLength = max(0, newRecordingOffset - maxLength)
            val firstCopyLength = numberRead - secondCopyLength
            // We store off all the data for the recognition thread to access. The ML
            // thread will copy out of this buffer into its own, while holding the
            // lock, so this should be thread safe.
            AudioBuffer.recordingBufferLock.lock()
            try {
                System.arraycopy(
                    audioBuffer, 0,
                    AudioBuffer.recordingBuffer,
                    AudioBuffer.recordingOffset, firstCopyLength
                )
                System.arraycopy(
                    audioBuffer, firstCopyLength,
                    AudioBuffer.recordingBuffer, 0, secondCopyLength
                )
                AudioBuffer.recordingOffset = newRecordingOffset % maxLength

            } catch (e: Error) {
                Log.e(logTag, "error record ${e.stackTrace}")
                initRecordingBuffer()
            } finally {
                AudioBuffer.recordingBufferLock.unlock()
            }
        }
        Log.v(logTag, "Recording finished.")
        record.stop()
        record.release()
    }

    /**
     * Initialize the recording buffer to zeroes
     */
    private fun initRecordingBuffer() {
        for (i in 0 until AudioBuffer.recordingLength) {
            AudioBuffer.recordingBuffer[i] = 0
        }
    }
}