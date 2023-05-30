package com.example.wuw_service_app.service.wuw.postprocessor

class ArgmaxPostProcessor(
    override val nConsecutiveWindows: Int,
    override val classNames: Array<String>,
    override val defaultClass: String,
    private val threshold: Float,
) : PostProcessor {
    override var logTag = "Pp-Argmax"
    private var previousLabel = defaultClass
    private var classCounter = 0

    override fun processScores(scores: FloatArray): String {
        return when (val maxScore = scores.maxOrNull()) {
            null -> defaultClass
            Float.NaN -> defaultClass
            0.0f -> defaultClass
            else -> {
                if (maxScore > threshold) {
                    val maxIdx = scores.asList().indexOf(maxScore)
                    val label = classNames[maxIdx]

                    if (label == previousLabel) {
                        classCounter = classCounter.inc()
                    } else {
                        classCounter = 1
                        previousLabel = label
                    }
                    if (classCounter >= nConsecutiveWindows) {
                        label
                    } else {
                        defaultClass
                    }
                } else {
                    previousLabel = defaultClass
                    defaultClass
                }
            }
        }
    }
}