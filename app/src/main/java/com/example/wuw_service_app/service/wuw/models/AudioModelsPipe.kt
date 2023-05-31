package com.example.wuw_service_app.service.wuw.models

/*
    This class will implement the audio model pipe to be executed each time that the audio is processed.
 */
class AudioModelsPipe(private val modelsList: List<AudioModelInterface?>) {
    // attributes

    // functions
    fun loadModels() {
        for (model in modelsList) {
            model?.loadModel()
        }
    }

    fun forward(buffer: FloatArray){
        for (model in modelsList) {
            model?.forward(buffer)
        }
    }
}