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

import android.util.Log
import com.example.wuw_service_app.service.wuw.models.AudioModelsPipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

class Predictor(private val audioModelsPipe: AudioModelsPipe) {

    private var logTag: String = "Inference thread"
    private var shouldContinuePredicting = false
    private var predictingThread: Thread? = null

    private val _predictorResponse = MutableStateFlow(PredictorResponse("", 0.0f))
    val predictorResponse: StateFlow<PredictorResponse> = _predictorResponse

    init {
        try {
            audioModelsPipe.loadModels()
        }
        catch (e: Error) {
            Log.e(logTag, "Error loading models ${e.stackTrace}")
        }

    }

    // Recognizing stuff
    @Synchronized
    fun startPredicting() {
        if (predictingThread != null) {
            return
        }
        shouldContinuePredicting = true
        predictingThread = Thread { predict() }
        predictingThread!!.start()
    }

    @Synchronized
    fun stopPredicting() {
        if (predictingThread == null) {
            return
        }
        shouldContinuePredicting = false
        predictingThread = null
    }

    private fun predict() {
        if (audioModelsPipe == null) {
            Log.d(logTag, "Model not loaded")
            return
        }
        val inputBuffer = ShortArray(AudioBuffer.recordingLength)

        // First time: wait until having a complete window
        Thread.sleep((AudioConstants.WINDOW_SIZE.times(1000)).toLong())

        while (shouldContinuePredicting) {
            try {
                AudioBuffer.read(inputBuffer)
            } catch (e: Error) {
                Log.e(logTag, "error record ${e.stackTrace}")
            }

            // Float conversion and normalization
            val modelInput = FloatArray(AudioBuffer.recordingLength)
            val peakValue = getPeakValue(inputBuffer.maxOrNull(), inputBuffer.minOrNull())
            for (i in inputBuffer.indices) {
                modelInput[i] = (inputBuffer[i].toFloat() / peakValue)
            }

            audioModelsPipe?.forward(modelInput)

            // Sleep until having a new valid audio chunk
            Thread.sleep(223)
        }
    }

    private fun getPeakValue(maxValue: Short?, minValue: Short?): Float {
        var max = maxValue
        var min = minValue

        if (max == null) {
            max = 32768.toShort()
        }
        if (min == null) {
            min = (-32768).toShort()
        }
        return maxOf(max.toFloat(), abs(min.toFloat()))
    }

    data class PredictorResponse(
        val label: String,
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis()
    )
}
