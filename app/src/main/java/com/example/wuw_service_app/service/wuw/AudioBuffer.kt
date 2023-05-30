package com.example.wuw_service_app.service.wuw

import java.util.concurrent.locks.ReentrantLock

/*
 Shared resource between Recorder and Predictor.
 */
object AudioBuffer {
    const val recordingLength = (AudioConstants.SAMPLE_RATE * AudioConstants.WINDOW_SIZE).toInt()
    var recordingBuffer = ShortArray(recordingLength)
    var recordingOffset = 0
    val recordingBufferLock = ReentrantLock()
}