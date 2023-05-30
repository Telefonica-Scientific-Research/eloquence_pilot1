package com.example.wuw_service_app.service.wuw.postprocessor

class ThresholdPostProcessor(
    override val nConsecutiveWindows: Int,
    override val classNames: Array<String>,
    override val defaultClass: String,
    private val thresholds: FloatArray
) : PostProcessor {
    override var logTag = "Pp-Threshold"
    private var arrayCounter = IntArray(classNames.size) { nConsecutiveWindows }

    override fun processScores(scores: FloatArray): String {
        for (i in arrayCounter.indices) {
            if (scores[i] >= thresholds[i]) {
                arrayCounter[i] += 1
            } else {
                arrayCounter[i] = 0
            }
        }

        var predictions = emptyList<String>()
        for ((index, value) in arrayCounter.withIndex()) {
            if (value >= nConsecutiveWindows) {
                predictions += classNames[index]
            }
        }

        if (predictions.isEmpty()) {
            predictions += defaultClass
        }

        return predictions.joinToString(" | ")
    }
}