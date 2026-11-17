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

package com.example.wuw_service_app.service.wuw.models

import android.util.Log
import org.pytorch.LiteModuleLoader
import org.pytorch.Module

interface AudioModelInterface {

    val logTag: String
    val modelPath: String
    var module: Module?
    val windowSize: Float
    val timeBetweenPredictionsMs: Long

    // Post processor
    val nConsecutiveWindows: Int
    val classesArray: Array<String>
    val defaultClass: String

    fun loadModel() {
        try {
            module = LiteModuleLoader.load(modelPath)
        } catch (e: Exception) {
            Log.e(logTag, "Error loading module: $e")
        }
    }

    fun forward(input: FloatArray, callback: AudioModelsCallback)

    fun onDetect()
}
