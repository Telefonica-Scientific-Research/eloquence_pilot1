package com.example.wuw_service_app

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import org.json.JSONObject
import android.content.Intent
import org.json.JSONArray
import org.json.JSONException
import java.util.concurrent.TimeUnit

/**
 * Singleton object that handles all network communication with the backend servers.
 * It manages both the "Wake Word" (short queries) and "Recording" (long-form conversation) flows.
 **/
object EloquenceAPI {
    private const val TAG = "EloquenceAPI"
    private const val BASE_IP = "192.168.24.120" // Server Addresses
    private const val URL_QUERY = "http://$BASE_IP:8082/query" // Transcription & RAG queries
    private const val URL_INGEST = "http://$BASE_IP:8082/ingest"
    private const val URL_DELETE_VS = "http://$BASE_IP:8082/delete_vs"
    private const val URL_LIST_VS = "http://$BASE_IP:8082/list_vs"
    private const val RETRIEVER_ADDRESS = "http://$BASE_IP:7997" // Knowledge base address

    // Stores the recent dialogue exchange to provide context to the LLM
    private val conversationHistory = mutableListOf<JSONArray>()

    // Configuration States - LLM & RAG (Defaults)
    // These values are controlled via the UI and determine how the AI behaves
    var currentLlmName: String = "Gemma 4 (TID)"
    var currentDocsK: Int = 5 // Number of relevant documents to retrieve
    var currentTemp: Double = 0.7 // Creativity level (0.0 = deterministic, 1.0 = creative)
    var currentTopP: Double = 0.9
    var currentMaxTokens: Int = 500 // Max length of the AI response
    var currentLanguage: String = "es"  // ISO code for transcription/response
    var currentIndexName: String = "index_name"

    // Configuration States - Vector Store (Defaults)
    // These determine how conversations are saved and indexed
    var currentChunkSize: Int = 500 // Max characters per text block in VS
    var currentEmbedName: String = "sentence-transformers/all-MiniLM-L6-v2"
    var currentSplittingStrategy: String = "recursive"
    var currentPercentile: Double = 0.9

    // HTTP Client configuration with long timeouts for heavy AI processing
    private val client = OkHttpClient.Builder()
        .connectTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * Resets the chat memory.
     */
    fun clearHistory() {
        conversationHistory.clear()
        Log.d(TAG, "Conversation history cleared.")
    }

    /**
     * Helper to create a standard JSON structure required by the backend API.
     */
    private fun createBaseJson(inputText: String) = JSONObject().apply {
        put("history", JSONArray()) // Empty history by default
        put("input_text", inputText)
        put("query", inputText)
        put("llm_name", currentLlmName)
        put("task_config", "Transcription")
        put("docs_k", currentDocsK)
        put("temp", currentTemp)
        put("top_p", currentTopP)
        put("max_tokens", currentMaxTokens)
        put("index_name", currentIndexName)
        put("system_prompt", JSONObject.NULL)
        put("language", currentLanguage)
    }

    // =========================================================================================
    // [WAKE WORD FLOW] - Triggered when the user says the wake-up word
    // =========================================================================================
    /**
     * Step 1: Send the short audio recording to Whisper for text transcription.
     */
    fun transcribeQueryWithWhisper(
        context: Context,
        audioBytes: ByteArray,
        language: String = currentLanguage,
        onTranscriptionReady: (String?) -> Unit
    ) {
        // Build the JSON metadata (json body with parameters)
        val json = createBaseJson("transcribe audio").apply {
            put("language", language)
            put("task_config", "Transcription")
            put("llm_name", "Whisper")
        }
        // Build the HTTP request body (json body + audio file)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio_file", "recording.wav", audioBytes.toRequestBody("audio/wav".toMediaTypeOrNull()))
            .addFormDataPart("body", json.toString())
            .build()

        Log.d(TAG, "[Wake Word Flow] Requesting Whisper: $json")
        val request = Request.Builder().url(URL_QUERY).post(requestBody).build()
        // Send the request to the Eloquence API (to be processed by Whisper)
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "[Wake Word Flow] Whisper failed: ${e.message}")
                onTranscriptionReady(null)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string() // Extract the response body. Converts the raw data (bytes) sent by the server into a readable String.
                    Log.d(TAG, "[Wake Word Flow] Whisper response: $body")
                    if (it.isSuccessful && body != null) {
                        // Extract the "text" field from the server's JSON response
                        val transcription = try {
                            // Try to parse the JSON response and extract the "text" field
                            JSONObject(body).optString("text", null)
                        } catch (e: JSONException) { null }
                        // Notify the UI that the transcription is ready
                        onTranscriptionReady(transcription?.trim())
                    } else {
                        onTranscriptionReady(null)
                    }
                }
            }
        })
    }

    /**
     * Step 2: Take the text from Step 1 and ask the LLM for an answer using RAG (Knowledge base).
     */
    fun getAnswerUsingRag(
        context: Context,
        transcribedText: String,
        conversation: String,
        address: String,
        llmName: String = currentLlmName,
        docsK: Int = currentDocsK,
        temp: Double = currentTemp,
        topP: Double = currentTopP,
        maxTokens: Int = currentMaxTokens,
        language: String = currentLanguage,
        showOnScreen: Boolean = true
    ) {
        // Build the JSON metadata (json body with parameters)
        val bodyData = JSONObject().apply {
            put("history", JSONArray().apply { conversationHistory.forEach { put(it) } }) // Include chat history
            put("input_text", transcribedText.trim())
            put("llm_name", llmName)
            put("task_config", "RAG") // Request Retrieval Augmented Generation
            put("render_doc_links", false)
            put("language", language)
            put("docs_k", docsK)
            put("temp", temp)
            put("top_p", topP)
            put("max_tokens", maxTokens)
            put("index_name", conversation) // Which knowledge base index to search
            put("retriever_address", address)
            put("system_prompt", "Te llamas Aura, un asistente virtual empático. Responde en español de forma breve y natural.")
        }

        Log.d(TAG, "[Wake Word Flow] Requesting RAG: $bodyData")
        val request = Request.Builder().url(URL_QUERY).post(FormBody.Builder().add("body", bodyData.toString()).build()).build()
        // Send the request to the Eloquence API (transcribed query be processed by RAG)
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "[Wake Word Flow] RAG request failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string() // Extract the response body. Converts the raw data (bytes) sent by the server into a readable String.
                    Log.d(TAG, "[Wake Word Flow] RAG response: $body")
                    // Safety Shield. The ?: "" is the "Elvis Operator". It says: "If the server somehow sent a null body, use an empty string "" instead.
                    // JSONObject(...) takes a string of text (like {"text": "Hello"}) and tries to "parse" it (turn it into a JSON).
                    val jsonResponse = try { JSONObject(body ?: "") } catch (e: Exception) { null }
                    val answer = jsonResponse?.optString("text", "") ?: "" // Extract the "text" field from the server's JSON response
                    if (answer.isNotBlank()) {
                        // Save to history so the next question knows what we just talked about
                        conversationHistory.add(JSONArray().put(transcribedText.trim()).put(answer))
                        if (showOnScreen) {
                            // Notify the UI to display the AI's answer (Android Broadcast Intent to send the information back to the UI)
                            context.sendBroadcast(Intent("com.example.wuw.LLM_RESPONSE").putExtra("LLM_TEXT", answer))
                        }
                    }
                }
            }
        })
    }

    // =========================================================================================
    // [RECORDING FLOW] - Triggered during long-form conversation recording
    // =========================================================================================

    /**
     * Step 1: Send a audio chunk to WhisperX for Diarized Transcription (Speaker ID).
     */
    fun transcribeAudioChunk(
        context: Context, 
        audioFile: File, 
        indexName: String = currentIndexName,
        language: String = currentLanguage, 
        append: Boolean = true,
        chunkSize: Int = currentChunkSize,
        embedName: String = currentEmbedName,
        splittingStrategy: String = currentSplittingStrategy,
        percentile: Double = currentPercentile
    ) {
        // Build the JSON metadata (json body with parameters)
        val json = createBaseJson("transcribe audio").apply {
            put("llm_name", "WhisperX")
            put("retriever_address", RETRIEVER_ADDRESS)
            put("task_config", "Diarization")
            put("language", language)
            put("index_name", indexName)
        }
        // Build the HTTP request body (json body + audio file)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio_file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaTypeOrNull()))
            .addFormDataPart("body", json.toString())
            .build()

        Log.d(TAG, "[Recording Flow] Requesting WhisperX for $indexName with config: $json")
        // Send the request to the Eloquence API (to be processed by WhisperX)
        client.newCall(Request.Builder().url(URL_QUERY).post(requestBody).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { 
                Log.e(TAG, "[Recording Flow] WhisperX failed: ${e.message}") // Clean up temp file on failure
                audioFile.delete() 
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string() // Extract the response body. Converts the raw data (bytes) sent by the server into a readable String.
                    Log.d(TAG, "[Recording Flow] WhisperX response: $body")
                    val text = try { JSONObject(body ?: "").optString("text", "") } catch (e: Exception) { "" }
                    if (text.trim().isNotBlank()) {
                        // Successfully transcribed. Now create a temp .txt file to store it.
                        val textFile = File(audioFile.parent, audioFile.nameWithoutExtension + ".txt")
                        // Write the transcription to the file
                        textFile.writeText(text.trim())
                        // Proceed to Step 2: Indexing the text into the Vector Store
                        ingestTextChunk(
                            context, 
                            textFile, 
                            indexName, 
                            audioFile, 
                            append,
                            chunkSize,
                            embedName,
                            splittingStrategy,
                            percentile
                        )
                    } else {
                        // No text heard? Delete the audio chunk.
                        audioFile.delete()
                    }
                }
            }
        })
    }

    /**
     * Step 2: Send the transcription text to the Vector Store for storage.
     */
    fun ingestTextChunk(
        context: Context, 
        textFile: File, 
        indexName: String = currentIndexName, 
        audioFile: File? = null,
        append: Boolean = true,
        chunkSize: Int = currentChunkSize,
        embedName: String = currentEmbedName,
        splittingStrategy: String = currentSplittingStrategy,
        percentile: Double = currentPercentile
    ) {
        // Build the JSON metadata (json body with parameters)
        val bodyData = JSONObject().apply {
            put("index_name", indexName)
            put("embed_name", embedName)
            put("chunk_size", chunkSize)
            put("splitting_strategy", splittingStrategy)
            put("percentile", percentile)
            put("retriever_address", RETRIEVER_ADDRESS)
            put("append", append) // If true, it adds to existing index. If false, it overwrites.
        }
        // Build the HTTP request body (body + text file)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("content_file", textFile.name, textFile.asRequestBody("text/plain".toMediaTypeOrNull()))
            .addFormDataPart("body", bodyData.toString())
            .build()

        Log.d(TAG, "[Recording Flow] Requesting Ingest for $indexName: $bodyData")
        // Send the request to the Eloquence API (text file to be ingested in Vector Store)
        client.newCall(Request.Builder().url(URL_INGEST).post(requestBody).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { 
                Log.e(TAG, "[Recording Flow] Ingest failed: ${e.message}")
                textFile.delete(); audioFile?.delete() // Cleanup on failure
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    Log.d(TAG, "[Recording Flow] Ingest response: ${it.code} - ${it.message}")
                    // CRITICAL: Cleanup files after successful upload to save disk space
                    textFile.delete(); audioFile?.delete()
                    if (it.isSuccessful) {
                        // Notify the UI that a chunk was successfully recorded and processed
                        context.sendBroadcast(Intent("com.example.wuw.CHUNK_SAVED"))
                    }
                }
            }
        })
    }

    // =========================================================================================
    // [CONVERSATION MANAGEMENT]
    // =========================================================================================
    /**
    * Deletes a specific index from the remote Vector Store.
    */
    fun deleteConversation(context: Context, indexName: String, retrieverAddress: String) {
        val url = URL_DELETE_VS.toHttpUrl().newBuilder()
            .addQueryParameter("index_name", indexName)
            .addQueryParameter("retriever_address", retrieverAddress)
            .build()
        Log.d(TAG, "[Management] Requesting Delete: $url")
        // Send the request to the Eloquence API (index to be deleted in vector store)
        client.newCall(Request.Builder().url(url).delete().build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "[Management] Delete request failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    Log.d(TAG, "[Management] Delete response: ${it.code}")
                    // Notify the UI that the index was successfully deleted
                    if (it.isSuccessful) context.sendBroadcast(Intent("com.example.wuw.STATUS_UPDATE").putExtra("STATUS_MESSAGE", "Deleted: $indexName"))
                }
            }
        })
    }

    /**
     * Fetches the list of indices from the Vector Store.
     * Matches format: {"available": ["idx1", "idx2"]}
     */
    fun listIndices(onResult: (List<String>?) -> Unit) {
        val url = URL_LIST_VS.toHttpUrl().newBuilder()
            .addQueryParameter("retriever_address", RETRIEVER_ADDRESS)
            .build()

        Log.d(TAG, "[Management] Requesting List: $url")
        // Send the request to the Eloquence API (list of indices in vector store)
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "[Management] List request failed: ${e.message}")
                onResult(null)
            }
            override fun onResponse(call: Call, response: Response) {
                // "use" is a Kotlin safety feature. It says: "Do everything inside these curly braces, and then automatically close the response pipe for me," even if something goes wrong.
                // This function takes a "messy" string from the internet, checks if it's valid, extracts the specific names of your recorded conversations, and hands them over to the UI to be displayed.
                response.use {
                    val body = it.body?.string() // Extract the response body. Converts the raw data (bytes) sent by the server into a readable String.
                    Log.d(TAG, "[Management] List response: $body")
                    if (it.isSuccessful && !body.isNullOrBlank()) {
                        try {
                            val json = JSONObject(body) // Turns that plain string into a "Smart Object" that Kotlin can understand
                            val array = json.optJSONArray("available") // looks specifically for a list inside the JSON named "available"
                            if (array != null) {
                                // Convert JSON array to Kotlin list (JSON Arrays are old-fashioned and clunky to use in Kotlin)
                                // Counts how many items are in the JSON array. Then loops through them. Pulls out each string. Puts them into a modern, clean Kotlin List<String>
                                val list = (0 until array.length()).map { array.getString(it) }
                                // Since network calls happen in the background, you can't just "return" a value. Instead, you use a Callback.
                                // A callback is like a "postman" (action) that carries that list back to your UI (MainViewModel) so it can be displayed on the screen.
                                onResult(list)
                            } else {
                                onResult(emptyList())
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[Management] JSON Parse error: ${e.message}")
                            onResult(null)
                        }
                    } else {
                        Log.e(TAG, "[Management] List error: ${it.code}")
                        onResult(null)
                    }
                }
            }
        })
    }
}
