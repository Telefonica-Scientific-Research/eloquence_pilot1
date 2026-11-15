package com.example.wuw_service_app.service.wuw

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

import kotlin.math.max

class Recorder() {

    // Logging
    private var logTag = "Recorder"

    // Recording thread variables
    private var shouldContinueRecording = false
    private var recordingThread: Thread? = null

    init {
        AudioBuffer.initAudioBuffer()
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
        Log.d(logTag, "Stop recording")
        if (recordingThread == null) {
            return
        }
        shouldContinueRecording = false
        recordingThread = null
    }

    // Make sure that the app has recording permission
    @SuppressLint("MissingPermission")
    private fun record() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        // Estimate the buffer size we'll need for this device.
        var bufferSize = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioConstants.CHANNELS,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = AudioConstants.SAMPLE_RATE * 2
        }
        val audioBuffer = ShortArray(bufferSize / 2)

        Log.v(logTag, "Recording buffer size is $bufferSize.")

        val record = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            AudioConstants.SAMPLE_RATE,
            AudioConstants.CHANNELS,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(logTag, "Audio Record can't initialize!")
            return
        }
        try {
            record.startRecording()
            Log.d(logTag, "Start recording")
        } catch (e: Error) {
            Log.e(logTag, "Error starting recorder thread ${e.stackTrace}")
        }

        // Loop, gathering audio data and copying it to a round-robin buffer.
        while (shouldContinueRecording) {
            val numberRead = record.read(audioBuffer, 0, audioBuffer.size)
            try {
                AudioBuffer.write(audioBuffer, numberRead)
            } catch (e: Error) {
                Log.e(logTag, "error record ${e.stackTrace}")
                AudioBuffer.initAudioBuffer()
            }
        }

        Log.d(logTag, "Recording finished.")
        record.stop()
        record.release()
    }
}