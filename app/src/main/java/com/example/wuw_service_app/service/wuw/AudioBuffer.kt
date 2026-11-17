// © 2026 Telefónica Innovación Digital S.L.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

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
