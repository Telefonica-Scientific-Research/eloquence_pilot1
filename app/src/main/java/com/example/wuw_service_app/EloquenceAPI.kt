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
import java.util.Locale

/**
 * Singleton object that handles all network communication with the backend servers.
 * It manages both the "Wake Word" (short queries) and "Recording" (long-form conversation) flows.
 **/
object EloquenceAPI {
    private const val TAG = "EloquenceAPI"

    var currentBaseIP: String = "https://eloquence.lt.bsc.es"

    private val URL_QUERY: String
        get() = if (currentBaseIP.startsWith("http")) "$currentBaseIP/query" else "http://$currentBaseIP:8082/query"
    private val URL_INGEST: String
        get() = if (currentBaseIP.startsWith("http")) "$currentBaseIP/ingest" else "http://$currentBaseIP:8082/ingest"
    private val URL_DELETE_VS: String
        get() = if (currentBaseIP.startsWith("http")) "$currentBaseIP/delete_vs" else "http://$currentBaseIP:8082/delete_vs"
    private val URL_LIST_VS: String
        get() = if (currentBaseIP.startsWith("http")) "$currentBaseIP/list_vs" else "http://$currentBaseIP:8082/list_vs"
    const val RETRIEVER_ADDRESS = "public"

    // Stores the recent dialogue exchange to provide context to the LLM
    private val conversationHistory = mutableListOf<JSONArray>()

    // Configuration States - LLM & RAG (Defaults)
    // These values are controlled via the UI and determine how the AI behaves
    var currentLlmName: String = "Gemma 4 31B"
    var currentTranscriberName: String = "Whisper"
    // Silence required before a spoken query is considered finished.
    var currentQuerySilenceDurationMs: Long = 2000L
    var currentDocsK: Int = 10 // Number of relevant documents to retrieve
    var currentTemp: Double = 0.7 // Creativity level (0.0 = deterministic, 1.0 = creative)
    var currentTopP: Double = 0.9
    var currentMaxTokens: Int = 1000 // Max length of the AI response
    var currentLanguage: String = "es"  // ISO code for transcription/response
    var currentIndexName: String = ""

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

    private fun formatSeconds(seconds: Double): String {
        val totalSeconds = seconds.toInt()
        val minutes = totalSeconds / 60
        val secs = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, secs)
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
     * Step 1: Send the short audio recording to the selected transcriber.
     */
    fun transcribeQueryWithTranscriber(
        context: Context,
        audioBytes: ByteArray,
        language: String = currentLanguage,
        transcriberName: String = currentTranscriberName,
        onTranscriptionReady: (String?) -> Unit
    ) {
        val json = createBaseJson("transcribe audio").apply {
            put("language", language)
            put("task_config", "Transcription")
            put("llm_name", transcriberName)
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio_file",
                "recording.wav",
                audioBytes.toRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .addFormDataPart("body", json.toString())
            .build()

        Log.d(
            TAG,
            "[Wake Word Flow] Requesting transcriber=$transcriberName: $json"
        )

        val request = Request.Builder()
            .url(URL_QUERY)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                Log.e(
                    TAG,
                    "[Wake Word Flow] Transcription with $transcriberName failed: ${e.message}"
                )
                onTranscriptionReady(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string()

                    Log.d(
                        TAG,
                        "[Wake Word Flow] $transcriberName response: $body"
                    )

                    if (it.isSuccessful && body != null) {
                        val transcription = try {
                            JSONObject(body).optString("text", null)
                        } catch (e: JSONException) {
                            null
                        }

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
        /*
         * A real selected conversation enables RAG.
         *
         * When there is no selected conversation, the question is sent directly
         * to the LLM without attempting vector retrieval.
         */
        val normalizedConversation = conversation.trim()

        val hasSelectedConversation =
            normalizedConversation.isNotEmpty() &&
                    normalizedConversation != "index_name"

        val selectedTask = if (hasSelectedConversation) {
            "RAG_with_Wiki"
        } else {
            "Wiki"
        }

        val bodyData = JSONObject().apply {
            put("history", JSONArray().apply {
                conversationHistory.forEach { put(it) }
            })

            put("input_text", transcribedText.trim())
            put("llm_name", llmName)
            put("task_config", selectedTask)
            put("render_doc_links", false)
            put("language", language)
            put("docs_k", docsK)
            put("temp", temp)
            put("top_p", topP)
            put("max_tokens", maxTokens)

            if (hasSelectedConversation) {
                put("index_name", normalizedConversation)
                put("retriever_address", address)
            } else {
                put("index_name", JSONObject.NULL)
            }

            put(
                "system_prompt",
                "Your name is Aura, an empathetic virtual assistant. " +
                        "Respond briefly and naturally in the same language as the question."
            )
        }

        Log.d(
            TAG,
            "[Wake Word Flow] Requesting $selectedTask. " +
                    "Conversation=${if (hasSelectedConversation) normalizedConversation else "none"}: $bodyData"
        )

        val request = Request.Builder()
            .url(URL_QUERY)
            .post(
                FormBody.Builder()
                    .add("body", bodyData.toString())
                    .build()
            )
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "[Wake Word Flow] RAG request failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string()
                    Log.d(TAG, "[Wake Word Flow] RAG response: $body")

                    val jsonResponse = try {
                        JSONObject(body ?: "")
                    } catch (e: Exception) {
                        Log.e(TAG, "[Wake Word Flow] Failed to parse RAG JSON response: ${e.message}")
                        null
                    }

                    val answer = jsonResponse?.optString("text", "") ?: ""
                    // APPLY IMPROVED POST-PROCESSING:
                    // This matches "[doc " followed by anything that is NOT a closing bracket "]"
                    var postprocessed_answer = answer.replace(
                        Regex("\\[doc\\s+([^\\]]+)\\]", RegexOption.IGNORE_CASE)
                    ) { matchResult ->
                        val innerContent = matchResult.groupValues[1]
                        val numbers =
                            Regex("\\d+")
                                .findAll(innerContent)
                                .map { it.value }
                                .toList()
                        "[${numbers.joinToString(",")}]"
                    }

                    // Convert common LaTeX-style arrows into normal Unicode characters.
                    postprocessed_answer = postprocessed_answer
                        .replace("\$\\rightarrow\$", "→")
                        .replace("\$\\leftarrow\$", "←")
                        .replace("\$\\leftrightarrow\$", "↔")
                        .replace("\$\\Rightarrow\$", "⇒")

                    // Extract retrieved RAG snippets from:
                    // "documents": ["snippet 1", "snippet 2", ...]
                    val documentsJsonArray = jsonResponse?.optJSONArray("documents")
                    val documents = ArrayList<String>()

                    if (documentsJsonArray != null) {
                        for (i in 0 until documentsJsonArray.length()) {
                            val documentText = documentsJsonArray.optString(i, "")
                            if (documentText.isNotBlank()) {
                                documents.add(documentText)
                            }
                        }
                    }

                    val documentsMetadataJsonArray = jsonResponse?.optJSONArray("documents_metadata")
                    val documentTitles = ArrayList<String>()

                    for (i in 0 until documents.size) {
                        val metadata = documentsMetadataJsonArray?.optJSONObject(i)
                        val startTime = metadata?.optDouble("start_time", Double.NaN) ?: Double.NaN

                        val title = if (!startTime.isNaN()) {
                            "Snippet ${i + 1} - Time ${formatSeconds(startTime)}"
                        } else {
                            "Snippet ${i + 1}"
                        }

                        documentTitles.add(title)
                    }

                    val error = jsonResponse?.optString("error", null)

                    if (postprocessed_answer.isNotBlank()) {
                        // Save to history so the next question knows what we just talked about
                        conversationHistory.add(
                            JSONArray()
                                .put(transcribedText.trim())
                                .put(postprocessed_answer)
                        )

                        if (showOnScreen) {
                            // Send both the LLM answer and the retrieved RAG snippets to the UI
                            val intent = Intent("com.example.wuw.LLM_RESPONSE").apply {
                                putExtra("LLM_TEXT", postprocessed_answer)
                                putStringArrayListExtra("LLM_DOCUMENTS", documents)
                                putStringArrayListExtra("LLM_DOCUMENT_TITLES", documentTitles)
                            }

                            context.sendBroadcast(intent)
                        }
                    } else {
                        Log.e(
                            TAG,
                            "[Wake Word Flow] Empty RAG answer. Error from server: $error"
                        )

                        if (showOnScreen) {
                            val fallbackMessage = if (!error.isNullOrBlank() && error != "null") {
                                "ERROR: $error"
                            } else {
                                "ERROR: Could not get an answer from the LLM+RAG"
                            }

                            val intent = Intent("com.example.wuw.STATUS_UPDATE").apply {
                                putExtra("STATUS_MESSAGE", fallbackMessage)
                            }

                            context.sendBroadcast(intent)
                        }
                    }
                }
            }
        })
    }

    // =========================================================================================
    // [RECORDING FLOW] - Triggered during long-form conversation recording
    // =========================================================================================

    enum class ChunkUploadResult {
        INGESTED,
        NO_SPEECH,
        FAILED
    }

    private fun reportRecordingError(context: Context, message: String) {
        Log.e(TAG, "[Recording Flow] $message")
        context.sendBroadcast(
            Intent("com.example.wuw.STATUS_UPDATE")
                .putExtra("STATUS_MESSAGE", "Recording error: $message")
        )
    }

    /**
     * Step 1: Send a audio chunk to WhisperX for Diarized Transcription (Speaker ID).
     */
    fun transcribeAudioChunk(
        context: Context,
        audioFile: File,
        indexName: String = currentIndexName,
        language: String = currentLanguage,
        append: Boolean,
        chunkIndex: Int,
        chunkStartSeconds: Double,
        chunkSize: Int = currentChunkSize,
        embedName: String = currentEmbedName,
        splittingStrategy: String = currentSplittingStrategy,
        percentile: Double = currentPercentile,
        onComplete: (ChunkUploadResult) -> Unit
    ) {
        val json = createBaseJson("transcribe audio").apply {
            put("llm_name", "WhisperX")
            put("retriever_address", RETRIEVER_ADDRESS)
            put("task_config", "Diarization")
            put("language", language)
            put("index_name", indexName)
            put("chunk_start_seconds", chunkStartSeconds)
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio_file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaTypeOrNull()))
            .addFormDataPart("body", json.toString())
            .build()

        Log.d(
            TAG,
            "[Recording Flow] Requesting WhisperX: index=$indexName, " +
                    "chunk=$chunkIndex, start=$chunkStartSeconds"
        )

        val request = Request.Builder().url(URL_QUERY).post(requestBody).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                reportRecordingError(
                    context,
                    "WhisperX failed for chunk $chunkIndex: ${e.message}"
                )
                onComplete(ChunkUploadResult.FAILED)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string()
                    Log.d(
                        TAG,
                        "[Recording Flow] WhisperX response for chunk " +
                                "$chunkIndex: ${it.code} - $responseBody"
                    )

                    if (!it.isSuccessful || responseBody.isNullOrBlank()) {
                        reportRecordingError(
                            context,
                            "WhisperX rejected chunk $chunkIndex with HTTP ${it.code}"
                        )
                        onComplete(ChunkUploadResult.FAILED)
                        return
                    }

                    val jsonResponse = try {
                        JSONObject(responseBody)
                    } catch (e: Exception) {
                        reportRecordingError(
                            context,
                            "Invalid WhisperX response for chunk $chunkIndex"
                        )
                        onComplete(ChunkUploadResult.FAILED)
                        return
                    }

                    val serverError = jsonResponse.optString("error", "").trim()
                    if (serverError.isNotEmpty() && serverError != "null") {
                        reportRecordingError(
                            context,
                            "WhisperX error for chunk $chunkIndex: $serverError"
                        )
                        onComplete(ChunkUploadResult.FAILED)
                        return
                    }

                    val text = jsonResponse.optString("text", "").trim()
                    if (text.isEmpty()) {
                        Log.d(TAG, "[Recording Flow] No speech found in chunk $chunkIndex")
                        audioFile.delete()
                        onComplete(ChunkUploadResult.NO_SPEECH)
                        return
                    }

                    val transcriptionTurns = jsonResponse.optJSONArray("transcription_turns")
                    if (transcriptionTurns == null || transcriptionTurns.length() == 0) {
                        reportRecordingError(
                            context,
                            "WhisperX returned text but no speaker turns for chunk $chunkIndex"
                        )
                        onComplete(ChunkUploadResult.FAILED)
                        return
                    }

                    val transcriptionMetadata = jsonResponse.optJSONObject("transcription_metadata")
                    val textFile = File(
                        audioFile.parent,
                        "${audioFile.nameWithoutExtension}.txt"
                    )

                    try {
                        textFile.writeText(text)
                    } catch (e: Exception) {
                        textFile.delete()
                        reportRecordingError(
                            context,
                            "Could not create transcription file for chunk $chunkIndex"
                        )
                        onComplete(ChunkUploadResult.FAILED)
                        return
                    }

                    ingestTextChunk(
                        context = context,
                        textFile = textFile,
                        indexName = indexName,
                        audioFile = audioFile,
                        append = append,
                        chunkIndex = chunkIndex,
                        chunkSize = chunkSize,
                        embedName = embedName,
                        splittingStrategy = splittingStrategy,
                        percentile = percentile,
                        transcriptionMetadata = transcriptionMetadata,
                        transcriptionTurns = transcriptionTurns
                    ) { successful ->
                        onComplete(
                            if (successful) ChunkUploadResult.INGESTED
                            else ChunkUploadResult.FAILED
                        )
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
        append: Boolean,
        chunkIndex: Int,
        chunkSize: Int = currentChunkSize,
        embedName: String = currentEmbedName,
        splittingStrategy: String = currentSplittingStrategy,
        percentile: Double = currentPercentile,
        transcriptionMetadata: JSONObject? = null,
        transcriptionTurns: JSONArray,
        onComplete: (Boolean) -> Unit
    ) {
        val bodyData = JSONObject().apply {
            put("index_name", indexName)
            put("embed_name", embedName)
            put("chunk_size", chunkSize)
            put("splitting_strategy", splittingStrategy)
            put("percentile", percentile)
            put("retriever_address", RETRIEVER_ADDRESS)
            put("append", append)
            put("chunk_index", chunkIndex)
            put("snippet_turns", transcriptionTurns)
            if (transcriptionMetadata != null) {
                put("snippet_metadata", transcriptionMetadata)
            }
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("content_file", textFile.name, textFile.asRequestBody("text/plain".toMediaTypeOrNull()))
            .addFormDataPart("body", bodyData.toString())
            .build()

        Log.d(
            TAG,
            "[Recording Flow] Requesting ingest: index=$indexName, " +
                    "chunk=$chunkIndex, append=$append"
        )

        val request = Request.Builder().url(URL_INGEST).post(requestBody).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                textFile.delete()
                reportRecordingError(
                    context,
                    "Ingestion failed for chunk $chunkIndex: ${e.message}"
                )
                onComplete(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string()
                    Log.d(
                        TAG,
                        "[Recording Flow] Ingest response for chunk " +
                                "$chunkIndex: ${it.code} - $responseBody"
                    )

                    val jsonResponse = try {
                        JSONObject(responseBody ?: "")
                    } catch (e: Exception) {
                        null
                    }
                    val successful =
                        it.isSuccessful && jsonResponse?.optString("status") == "success"

                    textFile.delete()

                    if (successful) {
                        audioFile?.delete()
                        context.sendBroadcast(Intent("com.example.wuw.CHUNK_SAVED"))
                    } else {
                        val message = jsonResponse?.optString("msg")
                            ?.takeIf { value -> value.isNotBlank() }
                            ?: "HTTP ${it.code}"
                        reportRecordingError(
                            context,
                            "Backend rejected chunk $chunkIndex: $message"
                        )
                    }

                    onComplete(successful)
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
