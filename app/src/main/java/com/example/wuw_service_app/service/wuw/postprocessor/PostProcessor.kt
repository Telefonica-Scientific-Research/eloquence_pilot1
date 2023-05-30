package com.example.wuw_service_app.service.wuw.postprocessor

interface PostProcessor {
    val logTag: String
    val nConsecutiveWindows: Int // consecutive windows to detect a class
    val classNames: Array<String> // class user representation
    val defaultClass: String // negative class by default

    fun processScores(scores: FloatArray): String

}