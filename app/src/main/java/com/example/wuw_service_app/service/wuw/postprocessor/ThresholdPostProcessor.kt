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
