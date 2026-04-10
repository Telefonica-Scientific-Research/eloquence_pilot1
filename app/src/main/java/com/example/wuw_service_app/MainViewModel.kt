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
    var chunkDurationSeconds = 10

    // Configuration States - LLM & RAG
    var llmName = mutableStateOf("Gemma 4 (TID)")
    var docsK = mutableStateOf("5")
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

    fun startConversationRecording() {
        showNamingDialog.value = true
    }

    fun dismissNamingDialog() {
        showNamingDialog.value = false
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
        // 1. Update UI state
        isConversationRecording.value = false
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
        EloquenceAPI.deleteConversation(context, indexName, "http://192.168.24.120:7997")
        viewModelScope.launch {
            delay(1500)
            fetchAndShowIndices()
        }
    }

    fun clearHistory() {
        EloquenceAPI.clearHistory()
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
        // 1. Update LLM configurations in AudioService
        val intent = Intent(context, AudioService::class.java)
        intent.putExtra("LLM_NAME", llmName.value)
        intent.putExtra("DOCS_K", docsK.value.toIntOrNull() ?: 5)
        intent.putExtra("TEMP", temperature.value.toDoubleOrNull() ?: 0.7)
        intent.putExtra("TOP_P", topP.value.toDoubleOrNull() ?: 0.9)
        intent.putExtra("MAX_TOKENS", maxTokens.value.toIntOrNull() ?: 1000)
        intent.putExtra("LANGUAGE", language.value)
        context.startForegroundService(intent)

        // ✅ 2. Update Vector Store configurations in EloquenceAPI
        EloquenceAPI.currentChunkSize = chunkSize.value.toIntOrNull() ?: 500
        EloquenceAPI.currentEmbedName = embedName.value
        EloquenceAPI.currentSplittingStrategy = splittingStrategy.value
        EloquenceAPI.currentPercentile = percentile.value.toDoubleOrNull() ?: 0.9

        // Update EloquenceAPI with LLM settings
        EloquenceAPI.currentLlmName = llmName.value
        EloquenceAPI.currentDocsK = docsK.value.toIntOrNull() ?: 5
        EloquenceAPI.currentTemp = temperature.value.toDoubleOrNull() ?: 0.7
        EloquenceAPI.currentTopP = topP.value.toDoubleOrNull() ?: 0.9
        EloquenceAPI.currentMaxTokens = maxTokens.value.toIntOrNull() ?: 1000
        EloquenceAPI.currentLanguage = language.value
        
        Log.d("MainViewModel", "Configurations updated: VS(chunk=${EloquenceAPI.currentChunkSize}, embed=${EloquenceAPI.currentEmbedName})")
    }
}
