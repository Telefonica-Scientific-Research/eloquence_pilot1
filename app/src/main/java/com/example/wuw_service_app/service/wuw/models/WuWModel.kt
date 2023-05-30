package com.example.wuw_service_app.service.wuw.models

import com.example.wuw_service_app.service.wuw.postprocessor.ArgmaxPostProcessor
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor


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

    // Specific to this model
    private var h: Tensor = getZeroesTensor(200)

    override fun forward(input: FloatArray): String {
        val x = IValue.from(Tensor.fromBlob(input, longArrayOf(1, 24000)))
        val memory = IValue.from(h)
        val before = SystemClock.elapsedRealtime()
        val outputTuple = module!!.forward(x, memory).toTuple()
        val forwardTime = SystemClock.elapsedRealtime() - before
        h = outputTuple[1].toTensor()
        val scores = outputTuple[0].toTensor().dataAsFloatArray
        return postProcessor.processScores(scores)
    }

    override fun onDetect() {
        TODO("Not yet implemented")
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