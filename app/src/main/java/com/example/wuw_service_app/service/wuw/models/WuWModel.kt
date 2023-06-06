package com.example.wuw_service_app.service.wuw.models

import android.util.Log
import com.example.wuw_service_app.service.wuw.AudioBuffer
import com.example.wuw_service_app.service.wuw.postprocessor.ArgmaxPostProcessor
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import kotlin.math.log


class WuWModel(override val modelPath: String): AudioModelInterface {
    override val logTag: String = "WUWModel"
    override var module: Module? = null
    override val windowSize: Float = 1.5F
    override val timeBetweenPredictionsMs: Long = 220

    // Post processing
    override val nConsecutiveWindows = 1
    override val classesArray = arrayOf("WUW", "NoWUW")
    override val defaultClass = "NoWUW"
    private val postProcessor =
        ArgmaxPostProcessor(nConsecutiveWindows, classesArray, defaultClass, 0.90f)

    // RNN: unused memory
    private val memory = IValue.from(getZeroesTensor(200))

    override fun forward(input: FloatArray, callback: AudioModelsCallback) {
        val x = IValue.from(Tensor.fromBlob(input, longArrayOf(1, 24000)))
        val outputTuple = module!!.forward(x, memory).toTuple()
        val scores = outputTuple[0].toTensor().dataAsFloatArray
        val label = postProcessor.processScores(scores)
        if (label == "WUW"){
            onDetect()
            callback
        }
    }

    override fun onDetect() {
        Log.d(logTag, "Wake-up word detected")
        AudioBuffer.initAudioBuffer()
        // cloud verification
        // trigger wuw detection flag
    }

    private fun getZeroesTensor(size: Int): Tensor {
        val hShape = longArrayOf(1, 1, size.toLong())
        return Tensor.fromBlob(getZeroesArray(size), hShape)
    }

    private fun getZeroesArray(size: Int): FloatArray {
        val zeroes = FloatArray(size)
        for (i in 0 until size) {
            zeroes[i] = 0f
        }
        return zeroes
    }
}
