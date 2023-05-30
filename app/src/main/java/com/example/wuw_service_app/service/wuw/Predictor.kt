package com.example.wuw_service_app.service.wuw

import android.util.Log
import com.example.wuw_service_app.service.wuw.models.WuWModel
import com.example.wuw_service_app.service.wuw.models.AudioModelInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

class Predictor(audioModelFactory: WuWModel, modelLabel: String) {

    private var logTag: String = "Predictor$modelLabel"
    private var shouldContinuePredicting = false
    private var predictingThread: Thread? = null
    private var audioModel: AudioModelInterface? = null

    private val _predictorResponse = MutableStateFlow(PredictorResponse("", 0.0f))
    val predictorResponse: StateFlow<PredictorResponse> = _predictorResponse

    init {
        audioModel = audioModelFactory.getModel(modelLabel)
        audioModel?.loadModel()
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
        if (audioModel == null) {
            Log.d(logTag, "Model not loaded")
            return
        }
        val inputBuffer = ShortArray(AudioBuffer.recordingLength)

        // First time: wait until having a complete window
        Thread.sleep((audioModel!!.windowSize.times(1000)).toLong())

        while (shouldContinuePredicting) {

            AudioBuffer.recordingBufferLock.lock()
            try {
                val maxLength = AudioBuffer.recordingBuffer.size
                val firstCopyLength = maxLength - AudioBuffer.recordingOffset
                val secondCopyLength = AudioBuffer.recordingOffset
                System.arraycopy(
                    AudioBuffer.recordingBuffer,
                    AudioBuffer.recordingOffset, inputBuffer, 0, firstCopyLength
                )
                System.arraycopy(
                    AudioBuffer.recordingBuffer,
                    0,
                    inputBuffer,
                    firstCopyLength,
                    secondCopyLength
                )
            } finally {
                AudioBuffer.recordingBufferLock.unlock()
            }

            // Float conversion and normalization
            val modelInput = FloatArray(AudioBuffer.recordingLength)
            val peakValue = getPeakValue(inputBuffer.maxOrNull(), inputBuffer.minOrNull())
            for (i in inputBuffer.indices) {
                modelInput[i] = (inputBuffer[i].toFloat() / peakValue)
            }


            val result = audioModel?.forward(modelInput)
            if (result != null) {
                if (result.label != audioModel?.defaultClass) {
                    _predictorResponse.value = PredictorResponse(result.label, result.scores.max())
                }
            }


            // Sleep until having a new valid audio chunk
            audioModel?.let { Thread.sleep(it.timeBetweenPredictionsMs) }
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