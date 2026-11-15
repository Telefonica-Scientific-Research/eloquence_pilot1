package com.example.wuw_service_app

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import com.example.wuw_service_app.service.wuw.AudioService
import android.util.Log

class MainViewModel : ViewModel() {
    private val _statusMessage = mutableStateOf("")
    val statusMessage: State<String> = _statusMessage

    private val _audioLevel = mutableStateOf(0f)
    val audioLevel: State<Float> = _audioLevel
    
    var isConversationRecording = mutableStateOf(false)
        private set

    private val _availableIndices = mutableStateOf<List<String>>(emptyList())
    val availableIndices: State<List<String>> = _availableIndices

    // Start with empty string so "Current Conversation" doesn't show initially
    var selectedIndex = mutableStateOf("")
    
    var showNamingDialog = mutableStateOf(false)
        private set

    private val messageQueue = mutableListOf<Pair<String, Long>>()
    private var isShowingMessage = false
    private var statusJob: Job? = null

    private val conversationRecorder = ConversationRecorder()

    var currentChunkCounter = 0
    var recordingIndexName = ""
    var chunkDurationSeconds = 30

    // Configuration States - LLM & RAG
    var baseIP = mutableStateOf("https://eloquence.lt.bsc.es")
    var llmName = mutableStateOf("Gemma 4 31B")
    var transcriberName = mutableStateOf("Whisper")
    var querySilenceSeconds = mutableStateOf("2.0")
    var docsK = mutableStateOf("10")
    var temperature = mutableStateOf("0.7")
    var topP = mutableStateOf("0.9")
    var maxTokens = mutableStateOf("1000") 
    var language = mutableStateOf("es")

    // ✅ Configuration States - Vector Store
    var chunkSize = mutableStateOf("500")
    var embedName = mutableStateOf("sentence-transformers/all-MiniLM-L6-v2")
    var splittingStrategy = mutableStateOf("recursive")
    var percentile = mutableStateOf("0.9")
    
    var showConfigDialog = mutableStateOf(false)

    private val _ragDocuments = mutableStateOf<List<String>>(emptyList())
    val ragDocuments: State<List<String>> = _ragDocuments
    private val _ragDocumentTitles = mutableStateOf<List<String>>(emptyList())
    val ragDocumentTitles: State<List<String>> = _ragDocumentTitles
    private val _ragAnswerForDialog = mutableStateOf("")
    val ragAnswerForDialog: State<String> = _ragAnswerForDialog
    var showRagDocumentsDialog = mutableStateOf(false)

    fun showLlmResponseWithDocuments(
        answer: String,
        documents: List<String>,
        documentTitles: List<String>,
        durationMillis: Long
    ) {
        _ragAnswerForDialog.value = answer
        _ragDocuments.value = documents

        _ragDocumentTitles.value = if (documentTitles.size == documents.size) {
            documentTitles
        } else {
            documents.indices.map { "Snippet ${it + 1}" }
        }

        // Keep the answer temporary, as before
        updateStatus(answer, durationMillis = durationMillis)
    }

    fun shouldShowRagSnippetsButton(currentStatus: String): Boolean {
        return currentStatus.isNotBlank() &&
                currentStatus == _ragAnswerForDialog.value &&
                _ragDocuments.value.isNotEmpty()
    }

    fun openRagDocumentsDialogFromCurrentAnswer() {
        if (_ragAnswerForDialog.value.isBlank()) return

        // Stop the temporary answer from continuing behind the dialog
        statusJob?.cancel()
        _statusMessage.value = ""
        messageQueue.clear()
        isShowingMessage = false

        showRagDocumentsDialog.value = true
    }

    fun closeRagDocumentsDialogAndClear() {
        showRagDocumentsDialog.value = false
        _ragAnswerForDialog.value = ""
        _ragDocuments.value = emptyList()
        _ragDocumentTitles.value = emptyList()

        statusJob?.cancel()
        _statusMessage.value = ""
        messageQueue.clear()
        isShowingMessage = false
    }

    fun dismissStatusAndClearRagIfNeeded() {
        val currentStatus = _statusMessage.value
        val wasCurrentRagAnswer = currentStatus == _ragAnswerForDialog.value

        dismissStatus()

        if (wasCurrentRagAnswer) {
            _ragAnswerForDialog.value = ""
            _ragDocuments.value = emptyList()
            _ragDocumentTitles.value = emptyList()
            showRagDocumentsDialog.value = false
        }
    }

    fun startConversationRecording() {
        showNamingDialog.value = true
    }

    fun dismissNamingDialog() {
        showNamingDialog.value = false
    }

    fun openRagDocumentsDialog() {
        showRagDocumentsDialog.value = true
    }

    fun closeRagDocumentsDialog() {
        showRagDocumentsDialog.value = false
    }

    fun clearRagDocuments() {
        _ragDocuments.value = emptyList()
        _ragDocumentTitles.value = emptyList()
        showRagDocumentsDialog.value = false
    }

    fun updateAudioLevel(level: Float, source: String = "unknown") {
        val cleanLevel = level.coerceIn(0f, 1f)

        // Important:
        // After pressing Stop Conversation, ignore late non-zero audio-level broadcasts
        // from ConversationRecorder. Otherwise the waveform can freeze at the last value.
        if (source == "conversation" && !isConversationRecording.value && cleanLevel > 0f) {
            return
        }

        _audioLevel.value = cleanLevel
    }

// In MainViewModel.kt

    fun actualStartRecording(context: Context, indexName: String, durationSeconds: Int) {
        // 1. Hide the dialog immediately
        showNamingDialog.value = false

        // 2. Update local UI state
        recordingIndexName = indexName
        chunkDurationSeconds = durationSeconds
        currentChunkCounter = 0
        isConversationRecording.value = true

        // 3. Update the selected context in UI and Service
        selectIndex(context, indexName)
        updateStatus("Starting recording: $indexName")

        // 4. Trigger the Service to start the background recording
        val intent = Intent(context, AudioService::class.java).apply {
            action = "ACTION_START_CONVERSATION"
            putExtra("INDEX_NAME", indexName)
            putExtra("DURATION", durationSeconds)
        }
        context.startForegroundService(intent)
    }

    fun stopConversationRecording(context: Context) {
        // 1. Update UI state immediately
        isConversationRecording.value = false
        updateAudioLevel(0f, source = "conversation")
        updateStatus("Recording stopped.")

        // 2. Trigger the Service to stop
        val intent = Intent(context, AudioService::class.java).apply {
            action = "ACTION_STOP_CONVERSATION"
        }
        context.startForegroundService(intent)

        // 3. Refresh the list of recordings
        fetchAndShowIndices()
    }

    fun deleteSelectedConversation(context: Context, indexName: String) {
        updateStatus("Deleting: $indexName")
        EloquenceAPI.deleteConversation(context, indexName,  EloquenceAPI.RETRIEVER_ADDRESS)
        viewModelScope.launch {
            delay(1500)
            fetchAndShowIndices()
        }
    }

    fun clearHistory() {
        EloquenceAPI.clearHistory()
        closeRagDocumentsDialogAndClear()
        updateStatus("History cleared.")
    }

    fun fetchAndShowIndices() {
        updateStatus("Fetching indices...")
        // Fetch indices from EloquenceAPI
        EloquenceAPI.listIndices { indices -> // // This is the 'onResult' part
            viewModelScope.launch { // Switch from background to UI thr
                if (indices != null) {
                    _availableIndices.value = indices // it's a MutableStateFlow. Think of it as a live variable. When its value changes, it automatically "pings" the UI to redraw itself.
                    updateStatus("List updated")
                } else {
                    updateStatus("Failed to fetch indices")
                }
            }
        }
    }

    fun selectIndex(context: Context, index: String) {
        selectedIndex.value = index
        updateStatus("Context set: $index")
        
        val intent = Intent(context, AudioService::class.java)
        intent.putExtra("SELECTED_INDEX", index)
        context.startForegroundService(intent)
    }

    fun updateStatus(message: String, durationMillis: Long = 2200L) {
        messageQueue.add(message to durationMillis)
        if (!isShowingMessage) showNextMessage()
    }

    private fun showNextMessage() {
        if (messageQueue.isEmpty()) {
            isShowingMessage = false
            return
        }
        isShowingMessage = true
        val (message, duration) = messageQueue.removeAt(0)
        _statusMessage.value = message
        statusJob = viewModelScope.launch {
            delay(duration)
            _statusMessage.value = ""
            delay(100)
            showNextMessage()
        }
    }

    /**
     * ✅ Dismiss the current status message and clear the queue.
     */
    fun dismissStatus() {
        statusJob?.cancel()
        _statusMessage.value = ""
        messageQueue.clear()
        isShowingMessage = false
    }
    
    fun incrementChunkAndShowStatus() {
        if (isConversationRecording.value) {
            currentChunkCounter++
            updateStatus("Chunk $currentChunkCounter saved")
        }
    }
    fun updateConfigurationsInService(context: Context) {

        val querySilenceSecondsValue =
            querySilenceSeconds.value.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?: 2.0
        querySilenceSeconds.value = querySilenceSecondsValue.toString()
        val querySilenceDurationMs =
            (querySilenceSecondsValue * 1000.0).toLong()

        val intent = Intent(context, AudioService::class.java)

        intent.putExtra("BASE_IP", baseIP.value)
        intent.putExtra("LLM_NAME", llmName.value)
        intent.putExtra("TRANSCRIBER_NAME", transcriberName.value)
        intent.putExtra(
            "QUERY_SILENCE_DURATION_MS",
            querySilenceDurationMs
        )

        intent.putExtra("DOCS_K", docsK.value.toIntOrNull() ?: 10)
        intent.putExtra("TEMP", temperature.value.toDoubleOrNull() ?: 0.7)
        intent.putExtra("TOP_P", topP.value.toDoubleOrNull() ?: 0.9)
        intent.putExtra("MAX_TOKENS", maxTokens.value.toIntOrNull() ?: 1000)
        intent.putExtra("LANGUAGE", language.value)

        context.startForegroundService(intent)

        // Vector Store
        EloquenceAPI.currentChunkSize =
            chunkSize.value.toIntOrNull() ?: 500

        EloquenceAPI.currentEmbedName = embedName.value
        EloquenceAPI.currentSplittingStrategy = splittingStrategy.value
        EloquenceAPI.currentPercentile =
            percentile.value.toDoubleOrNull() ?: 0.9

        // LLM / RAG
        EloquenceAPI.currentBaseIP = baseIP.value
        EloquenceAPI.currentLlmName = llmName.value
        EloquenceAPI.currentDocsK = docsK.value.toIntOrNull() ?: 10
        EloquenceAPI.currentTemp = temperature.value.toDoubleOrNull() ?: 0.7
        EloquenceAPI.currentTopP = topP.value.toDoubleOrNull() ?: 0.9
        EloquenceAPI.currentMaxTokens =
            maxTokens.value.toIntOrNull() ?: 1000
        EloquenceAPI.currentLanguage = language.value

        // Query transcription
        EloquenceAPI.currentTranscriberName = transcriberName.value
        EloquenceAPI.currentQuerySilenceDurationMs =
            querySilenceDurationMs

        Log.d(
            "MainViewModel",
            "Configurations updated: " +
                    "transcriber=${EloquenceAPI.currentTranscriberName}, " +
                    "querySilence=${EloquenceAPI.currentQuerySilenceDurationMs}ms"
        )
        }
    }
