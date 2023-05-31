package com.example.wuw_service_app.service.wuw

import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

/*
 Shared resource between Recorder and Predictor.
 Implements a Round-robin buffer.
 */
object AudioBuffer {
    const val recordingLength = (AudioConstants.SAMPLE_RATE * AudioConstants.WINDOW_SIZE).toInt()
    private var recordingBuffer = ShortArray(recordingLength)
    private val recordingBufferLock = ReentrantLock()
    private var recordingOffset = 0

    //fer
    private val maxLength = recordingBuffer.size


    fun write(audioBuffer: ShortArray, numberRead: Int){
        val newRecordingOffset = recordingOffset + numberRead
        val secondCopyLength = max(0, newRecordingOffset - maxLength)
        val firstCopyLength = numberRead - secondCopyLength

        // We store off all the data for the recognition thread to access. The ML
        // thread will copy out of this buffer into its own, while holding the
        // lock, so this should be thread safe.
        recordingBufferLock.lock()
        System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, firstCopyLength)
        System.arraycopy(audioBuffer, firstCopyLength, recordingBuffer, 0, secondCopyLength)
        recordingOffset = newRecordingOffset % maxLength
        recordingBufferLock.unlock()
    }

    fun read(inputBuffer: ShortArray){
        recordingBufferLock.lock()
        val firstCopyLength = maxLength - recordingOffset
        val secondCopyLength = recordingOffset
        System.arraycopy(recordingBuffer, recordingOffset, inputBuffer, 0, firstCopyLength)
        System.arraycopy(recordingBuffer, 0, inputBuffer, firstCopyLength, secondCopyLength)
        recordingBufferLock.unlock()
    }

    fun initAudioBuffer(){
        recordingBufferLock.lock()
        for (i in 0 until recordingLength) {
            recordingBuffer[i] = 0
        }
        recordingBufferLock.unlock()
    }
}