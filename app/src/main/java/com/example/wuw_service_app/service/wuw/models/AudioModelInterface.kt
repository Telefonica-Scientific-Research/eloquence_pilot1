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