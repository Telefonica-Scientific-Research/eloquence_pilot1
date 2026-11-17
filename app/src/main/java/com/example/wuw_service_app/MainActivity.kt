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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.wuw_service_app.service.wuw.AudioService
import com.example.wuw_service_app.ui.theme.WuwserviceappTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val RECORD_AUDIO_REQUEST_CODE = 1


    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.wuw.STATUS_UPDATE" -> {
                    val message = intent.getStringExtra("STATUS_MESSAGE")
                    message?.let { viewModel.updateStatus(it) }
                }
                "com.example.wuw.LLM_RESPONSE" -> {
                    val llmText = intent.getStringExtra("LLM_TEXT")
                    val documents = intent.getStringArrayListExtra("LLM_DOCUMENTS") ?: arrayListOf()
                    val documentTitles = intent.getStringArrayListExtra("LLM_DOCUMENT_TITLES") ?: arrayListOf()

                    llmText?.let {
                        val calculatedDuration = (3000L + it.length * 80L).coerceIn(5000L, 30000L)

                        viewModel.showLlmResponseWithDocuments(
                            answer = it,
                            documents = documents,
                            documentTitles = documentTitles,
                            durationMillis = calculatedDuration
                        )
                    }
                }
                "com.example.wuw.CHUNK_SAVED" -> {
                    viewModel.incrementChunkAndShowStatus()
                }
                "com.example.wuw.AUDIO_LEVEL" -> {
                    val level = intent.getFloatExtra("AUDIO_LEVEL", 0f)
                    val source = intent.getStringExtra("AUDIO_LEVEL_SOURCE") ?: "unknown"
                    viewModel.updateAudioLevel(level, source)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasMicPermission()) {
            initializeApp()
        } else {
            requestMicPermission()
        }
    }

    private fun initializeApp() {
        registerReceiver(statusReceiver, IntentFilter("com.example.wuw.STATUS_UPDATE"))
        registerReceiver(statusReceiver, IntentFilter("com.example.wuw.LLM_RESPONSE"))
        registerReceiver(statusReceiver, IntentFilter("com.example.wuw.CHUNK_SAVED"))
        registerReceiver(statusReceiver, IntentFilter("com.example.wuw.AUDIO_LEVEL"))

        setContent {
            WuwserviceappTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DesignPrimaryPastelGreen
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
        startAudioService()
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }

    private fun startAudioService() {
        val serviceIntent = Intent(this@MainActivity, AudioService::class.java)
        startForegroundService(serviceIntent)
    }
}

val DesignPrimaryOrange = Color(0xFFE87919)
val DesignPrimaryPastelGreen = Color(0xFFE8F5E9)

// Updated Design Colors
val DesignPrimaryGreen = Color(0xFF008966) // The dark green from the button
val DesignBackground = Color(0xFFF7F9F7)    // The soft off-white background
val DesignCardWhite = Color(0xFFFFFFFF)    // Pure white for the center card
val DesignTextGray = Color(0xFF6B7280)     // Gray for subtitles
val DesignBorderGray = Color(0xFFE5E7EB)   // Light gray for dividers
val DesignRed = Color(0xFFDC2626)          // Red for delete/clear actions

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current

    val status by viewModel.statusMessage
    val isRecording by viewModel.isConversationRecording
    val indices by viewModel.availableIndices
    val selectedIndex by viewModel.selectedIndex
    val showNamingDialog by viewModel.showNamingDialog
    val showConfigDialog by viewModel.showConfigDialog
    val audioLevel by viewModel.audioLevel
    val ragDocuments by viewModel.ragDocuments
    val ragDocumentTitles by viewModel.ragDocumentTitles
    val showRagDocumentsDialog by viewModel.showRagDocumentsDialog
    val ragAnswerForDialog by viewModel.ragAnswerForDialog

    var showIndicesDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var newIndexName by remember { mutableStateOf("") }
    var chunkDuration by remember { mutableStateOf("30") }

    Scaffold(
        containerColor = DesignBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: title
                Box(
                    modifier = Modifier.weight(1.85f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column {
                        Text(
                            text = "Pilot 1: Smart Home Assistant",
                            fontWeight = FontWeight.Bold,
                            fontSize = 25.sp,
                            color = Color.Black,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "To ask Aura, say “OK, Aura” followed by your question.",
                            fontSize = 12.sp,
                            color = DesignTextGray,
                            maxLines = 1
                        )

                        if (selectedIndex.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))

                            Surface(
                                color = DesignPrimaryOrange.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "Current: $selectedIndex",
                                    color = DesignPrimaryOrange,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Center: compact logo
                Box(
                    modifier = Modifier
                        .weight(0.65f)
                        .offset(x = 33.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TopBarLogo()
                }

                // Right: configurations button
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    OutlinedButton(
                        onClick = { viewModel.showConfigDialog.value = true },
                        border = BorderStroke(1.dp, DesignPrimaryGreen.copy(alpha = 0.35f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White,
                            contentColor = DesignPrimaryGreen
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configurations",
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = "Configurations",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    ) { padding ->

        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
                colors = CardDefaults.cardColors(containerColor = DesignCardWhite),
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val activeColor = if (isRecording) DesignPrimaryOrange else DesignPrimaryGreen

                    WaveformMicMeter(
                        level = audioLevel,
                        activeColor = activeColor,
                        isRecording = isRecording,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            if (!isRecording) {
                                viewModel.startConversationRecording()
                            } else {
                                viewModel.stopConversationRecording(context)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(66.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) DesignPrimaryOrange else DesignPrimaryGreen
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isRecording) "Stop Conversation" else "Record Conversation",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                    }

                    Text(
                        text = if (isRecording)
                            "Recording is active. Tap to stop and save."
                        else
                            "Tap to start recording a conversation for your smart home assistant",
                        color = DesignTextGray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.fetchAndShowIndices()
                                showIndicesDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            border = BorderStroke(1.dp, DesignPrimaryGreen),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = DesignPrimaryGreen
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Change", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.fetchAndShowIndices()
                                showDeleteDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            border = BorderStroke(1.dp, DesignRed),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = DesignRed
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete", fontSize = 13.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedButton(
                        onClick = { viewModel.clearHistory() },
                        border = BorderStroke(
                            1.dp,
                            DesignRed.copy(alpha = 0.45f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = DesignRed
                        ),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(
                            horizontal = 24.dp,
                            vertical = 12.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "Clear History",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Disclaimer is OUTSIDE the button
                    Spacer(modifier = Modifier.height(18.dp))

                    Divider(
                        color = DesignBorderGray
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "You are interacting with an AI assistant. " +
                                "Responses may contain errors. Verify important information.",
                        color = DesignTextGray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 15.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (status.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-240).dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    StatusBanner(
                        status = status,
                        onDismiss = { viewModel.dismissStatusAndClearRagIfNeeded() }
                    )

                    if (viewModel.shouldShowRagSnippetsButton(status)) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { viewModel.openRagDocumentsDialogFromCurrentAnswer() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DesignPrimaryGreen
                            ),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "View retrieved snippets",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showConfigDialog) {
        ConfigDialog(
            viewModel = viewModel,
            context = context,
            onDismiss = { viewModel.showConfigDialog.value = false }
        )
    }

    if (showRagDocumentsDialog) {
        RagSnippetsDialog(
            answer = ragAnswerForDialog,
            documents = ragDocuments,
            documentTitles = ragDocumentTitles,
            onDismiss = { viewModel.closeRagDocumentsDialogAndClear() }
        )
    }

    if (showNamingDialog) {
        NamingDialog(
            newIndexName = newIndexName,
            onNameChange = { newIndexName = it },
            chunkDuration = chunkDuration,
            onChunkDurationChange = { chunkDuration = it },
            onStart = {
                val sanitizedName = newIndexName.trim()
                    .lowercase()
                    .replace(" ", "_")
                    .replace(Regex("[^a-z0-9_]"), "")

                if (sanitizedName.isNotBlank()) {
                    val duration = chunkDuration.toIntOrNull() ?: 10

                    val timestamp = SimpleDateFormat(
                        "dd-MM-yyyy_HH:mm",
                        Locale.getDefault()
                    ).format(Date())

                    val nameWithTimestamp = "${sanitizedName}_$timestamp"

                    viewModel.actualStartRecording(context, nameWithTimestamp, duration)

                    newIndexName = ""
                    chunkDuration = "30"
                }
            },
            onDismiss = {
                newIndexName = ""
                chunkDuration = "30"
                viewModel.dismissNamingDialog()
            }
        )
    }

    if (showIndicesDialog) {
        ConversationPickerDialog(
            title = "Select Conversation Context",
            emptyText = "No conversations found in Vector Store.",
            indices = indices,
            selectedIndex = selectedIndex,
            isDeleteMode = false,
            onDismiss = { showIndicesDialog = false },
            onSelected = { index ->
                viewModel.selectIndex(context, index)
                showIndicesDialog = false
            }
        )
    }

    if (showDeleteDialog) {
        ConversationPickerDialog(
            title = "Select Conversation to Delete",
            emptyText = "No conversations available to delete.",
            indices = indices,
            selectedIndex = selectedIndex,
            isDeleteMode = true,
            onDismiss = { showDeleteDialog = false },
            onSelected = { index ->
                viewModel.deleteSelectedConversation(context, index)
                showDeleteDialog = false
            }
        )
    }
}

@Composable
private fun TopBarLogo() {
    Image(
        painter = painterResource(id = R.drawable.eloquence_logo_horizontal),
        contentDescription = "Eloquence logo",
        modifier = Modifier
            .height(70.dp)
            .width(300.dp),
        contentScale = ContentScale.Fit
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ConfigDialog(
    viewModel: MainViewModel,
    context: Context,
    onDismiss: () -> Unit
) {
    var languageExpanded by remember { mutableStateOf(false) }

    val languages = listOf("en", "es", "it")

    val serverOptions = listOf(
        "https://eloquence.lt.bsc.es",
        "192.168.24.120"
    )

    val transcriberOptions = listOf(
        "Whisper",
        "MEUSLI-SLAM-ASR"
    )

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DesignCardWhite,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 24.dp),
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        ),
        shape = RoundedCornerShape(28.dp),

        title = {
            Column {
                Text(
                    text = "Configurations",
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Adjust model, transcription, retrieval and vector store settings.",
                    fontSize = 13.sp,
                    color = DesignTextGray
                )
            }
        },

        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(scrollState)
            ) {

                // =========================================================
                // LLM & RAG
                // =========================================================
                ConfigSectionCard(
                    title = "LLM & RAG",
                    subtitle = "Server address, language model and retrieval parameters"
                ) {

                    ConfigDropdownField(
                        label = "Server Base IP",
                        value = viewModel.baseIP.value,
                        options = serverOptions,
                        onSelected = {
                            viewModel.baseIP.value = it
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ConfigTextField(
                        label = "LLM Name",
                        value = viewModel.llmName.value,
                        onValueChange = {
                            viewModel.llmName.value = it
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ConfigTextField(
                            label = "docs_k",
                            value = viewModel.docsK.value,
                            onValueChange = {
                                viewModel.docsK.value = it
                            },
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )

                        ConfigTextField(
                            label = "Temperature",
                            value = viewModel.temperature.value,
                            onValueChange = {
                                viewModel.temperature.value = it
                            },
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ConfigTextField(
                            label = "top_p",
                            value = viewModel.topP.value,
                            onValueChange = {
                                viewModel.topP.value = it
                            },
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f)
                        )

                        ConfigTextField(
                            label = "max_tokens",
                            value = viewModel.maxTokens.value,
                            onValueChange = {
                                viewModel.maxTokens.value = it
                            },
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Language",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color.DarkGray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Box {
                        OutlinedButton(
                            onClick = {
                                languageExpanded = true
                            },
                            border = BorderStroke(
                                1.dp,
                                DesignPrimaryGreen.copy(alpha = 0.45f)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White,
                                contentColor = DesignPrimaryGreen
                            ),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(
                                horizontal = 22.dp,
                                vertical = 10.dp
                            )
                        ) {
                            Text(
                                text = viewModel.language.value,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        DropdownMenu(
                            expanded = languageExpanded,
                            onDismissRequest = {
                                languageExpanded = false
                            }
                        ) {
                            languages.forEach { lang ->

                                val isSelected =
                                    lang == viewModel.language.value

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = lang,
                                            color =
                                                if (isSelected) {
                                                    DesignPrimaryGreen
                                                } else {
                                                    Color.Black
                                                },
                                            fontWeight =
                                                if (isSelected) {
                                                    FontWeight.Bold
                                                } else {
                                                    FontWeight.Normal
                                                }
                                        )
                                    },
                                    onClick = {
                                        viewModel.language.value = lang
                                        languageExpanded = false
                                    },
                                    modifier = Modifier.background(
                                        color =
                                            if (isSelected) {
                                                DesignPrimaryGreen.copy(
                                                    alpha = 0.10f
                                                )
                                            } else {
                                                Color.White
                                            }
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // =========================================================
                // TRANSCRIBER
                // =========================================================
                ConfigSectionCard(
                    title = "Transcriber",
                    subtitle = "Speech-to-text model used for spoken assistant queries"
                ) {

                    ConfigDropdownField(
                        label = "Model name",
                        value = viewModel.transcriberName.value,
                        options = transcriberOptions,
                        onSelected = {
                            viewModel.transcriberName.value = it
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // =========================================================
                // QUERY ENDPOINTING
                // =========================================================
                ConfigSectionCard(
                    title = "Query Endpointing",
                    subtitle = "Controls when a spoken query is considered complete"
                ) {

                    ConfigTextField(
                        label = "Silence duration (seconds)",
                        value = viewModel.querySilenceSeconds.value,
                        onValueChange = {
                            viewModel.querySilenceSeconds.value = it
                        },
                        keyboardType = KeyboardType.Decimal
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Continuous silence required before query recording stops.",
                        fontSize = 11.sp,
                        color = DesignTextGray
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // =========================================================
                // VECTOR STORE
                // =========================================================
                ConfigSectionCard(
                    title = "Vector Store",
                    subtitle = "Embedding and chunking configuration"
                ) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ConfigTextField(
                            label = "chunk_size",
                            value = viewModel.chunkSize.value,
                            onValueChange = {
                                viewModel.chunkSize.value = it
                            },
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )

                        ConfigTextField(
                            label = "percentile",
                            value = viewModel.percentile.value,
                            onValueChange = {
                                viewModel.percentile.value = it
                            },
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ConfigTextField(
                        label = "embed_name",
                        value = viewModel.embedName.value,
                        onValueChange = {
                            viewModel.embedName.value = it
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ConfigTextField(
                        label = "splitting_strategy",
                        value = viewModel.splittingStrategy.value,
                        onValueChange = {
                            viewModel.splittingStrategy.value = it
                        }
                    )
                }
            }
        },

        confirmButton = {
            Button(
                onClick = {
                    viewModel.updateConfigurationsInService(context)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DesignPrimaryGreen
                ),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(
                    horizontal = 24.dp,
                    vertical = 10.dp
                )
            ) {
                Text(
                    text = "Save",
                    fontWeight = FontWeight.Bold
                )
            }
        },

        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(
                    text = "Close",
                    color = DesignPrimaryGreen,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
private fun ConfigSectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = DesignBackground
        ),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, DesignBorderGray.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.Black
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = DesignTextGray
            )

            Spacer(modifier = Modifier.height(16.dp))

            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.textFieldColors(
            containerColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedLabelColor = DesignPrimaryGreen,
            cursorColor = DesignPrimaryGreen
        )
    )
}

@Composable
private fun ConfigDropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = Color.DarkGray
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(
                    1.dp,
                    DesignPrimaryGreen.copy(alpha = 0.45f)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 13.dp
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = value,
                        fontSize = 14.sp
                    )

                    Text(
                        text = "▼",
                        color = DesignPrimaryGreen,
                        fontSize = 11.sp
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    val isSelected = option == value

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                fontWeight = if (isSelected) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                                color = if (isSelected) {
                                    DesignPrimaryGreen
                                } else {
                                    Color.Black
                                }
                            )
                        },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NamingDialog(
    newIndexName: String,
    onNameChange: (String) -> Unit,
    chunkDuration: String,
    onChunkDurationChange: (String) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    val textFieldColors = TextFieldDefaults.textFieldColors(
        containerColor = Color.White,
        focusedIndicatorColor = DesignPrimaryGreen,
        focusedLabelColor = DesignPrimaryGreen
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DesignCardWhite,
        title = {
            Text("Configure Conversation")
        },
        text = {
            Column {
                Text("Conversation name:")

                Spacer(modifier = Modifier.height(6.dp))

                TextField(
                    value = newIndexName,
                    onValueChange = onNameChange,
                    placeholder = { Text("Enter name...") },
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Chunk duration seconds:")

                Spacer(modifier = Modifier.height(6.dp))

                TextField(
                    value = chunkDuration,
                    onValueChange = onChunkDurationChange,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = DesignPrimaryGreen)
            ) {
                Text("Start Recording")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RagSnippetsDialog(
    answer: String,
    documents: List<String>,
    documentTitles: List<String>,
    onDismiss: () -> Unit
) {
    var selectedDocumentIndex by remember { mutableStateOf(0) }

    val safeSelectedIndex = selectedDocumentIndex.coerceIn(
        0,
        (documents.size - 1).coerceAtLeast(0)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DesignCardWhite,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 20.dp),
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        ),
        shape = RoundedCornerShape(28.dp),
        title = {
            Column {
                Text(
                    text = "Retrieved Snippets by RAG engine",
                    fontWeight = FontWeight.Bold,
                    fontSize = 25.sp,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Compare Aura's answer with the retrieved snippets.",
                    fontSize = 13.sp,
                    color = DesignTextGray
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = DesignPrimaryGreen.copy(alpha = 0.08f)
                    ),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(
                        1.dp,
                        DesignPrimaryGreen.copy(alpha = 0.18f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "Aura answer",
                            color = DesignPrimaryGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = answer,
                            color = Color.Black,
                            fontSize = 15.sp,
                            lineHeight = 21.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (documents.isEmpty()) {
                    Text(
                        text = "No retrieved snippets available for this response.",
                        color = DesignTextGray,
                        fontSize = 14.sp
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(0.9f)
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(documents.indices.toList()) { index ->
                                val isSelected = index == safeSelectedIndex

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedDocumentIndex = index },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) {
                                            DesignPrimaryGreen.copy(alpha = 0.12f)
                                        } else {
                                            DesignBackground
                                        }
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (isSelected) {
                                            DesignPrimaryGreen.copy(alpha = 0.55f)
                                        } else {
                                            DesignBorderGray
                                        }
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                ) {
                                    Text(
                                        text = documentTitles.getOrElse(index) { "Snippet ${index + 1}" },
                                        color = if (isSelected) DesignPrimaryGreen else Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        }

                        Card(
                            modifier = Modifier
                                .weight(2f)
                                .heightIn(min = 260.dp, max = 360.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = DesignBackground
                            ),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(
                                1.dp,
                                DesignBorderGray.copy(alpha = 0.8f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = documentTitles.getOrElse(safeSelectedIndex) { "Snippet ${safeSelectedIndex + 1}" },
                                    color = DesignPrimaryGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = documents[safeSelectedIndex],
                                    color = Color.Black,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Close",
                    color = DesignPrimaryGreen,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConversationPickerDialog(
    title: String,
    emptyText: String,
    indices: List<String>,
    selectedIndex: String,
    isDeleteMode: Boolean,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    val accentColor = if (isDeleteMode) DesignRed else DesignPrimaryGreen

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DesignCardWhite,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 24.dp),
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        ),
        shape = RoundedCornerShape(28.dp),
        title = {
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (isDeleteMode) {
                        "Choose a conversation to permanently remove."
                    } else {
                        "Choose the conversation context you want to use."
                    },
                    fontSize = 13.sp,
                    color = DesignTextGray
                )
            }
        },
        text = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = DesignBackground
                ),
                shape = RoundedCornerShape(22.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, DesignBorderGray.copy(alpha = 0.8f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = accentColor.copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isDeleteMode) {
                                        Icons.Default.DeleteOutline
                                    } else {
                                        Icons.Outlined.ChatBubbleOutline
                                    },
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Text(
                                text = if (isDeleteMode) {
                                    "Available conversations"
                                } else {
                                    "Conversation contexts"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = Color.Black
                            )

                            Text(
                                text = if (indices.isEmpty()) {
                                    "No items available"
                                } else {
                                    "${indices.size} found"
                                },
                                fontSize = 12.sp,
                                color = DesignTextGray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (indices.isEmpty()) {
                        EmptyConversationState(
                            text = emptyText,
                            isDeleteMode = isDeleteMode
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(indices) { index ->
                                ConversationListRow(
                                    index = index,
                                    selectedIndex = selectedIndex,
                                    isDeleteMode = isDeleteMode,
                                    onClick = { onSelected(index) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Close",
                    color = DesignPrimaryGreen,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
private fun ConversationListRow(
    index: String,
    selectedIndex: String,
    isDeleteMode: Boolean,
    onClick: () -> Unit
) {
    val isSelected = index == selectedIndex
    val accentColor = if (isDeleteMode) DesignRed else DesignPrimaryGreen

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected && !isDeleteMode) {
                DesignPrimaryGreen.copy(alpha = 0.10f)
            } else {
                Color.White
            }
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                isDeleteMode -> DesignRed.copy(alpha = 0.25f)
                isSelected -> DesignPrimaryGreen.copy(alpha = 0.55f)
                else -> DesignBorderGray
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = accentColor.copy(alpha = 0.10f),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isDeleteMode) {
                            Icons.Default.DeleteOutline
                        } else {
                            Icons.Outlined.ChatBubbleOutline
                        },
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = index,
                    color = if (isDeleteMode) DesignRed else Color.Black,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 16.sp
                )

                if (isSelected && !isDeleteMode) {
                    Text(
                        text = "Currently selected",
                        color = DesignPrimaryGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (isSelected && !isDeleteMode) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = DesignPrimaryGreen.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "Active",
                        color = DesignPrimaryGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyConversationState(
    text: String,
    isDeleteMode: Boolean
) {
    val accentColor = if (isDeleteMode) DesignRed else DesignPrimaryGreen

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = accentColor.copy(alpha = 0.10f),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isDeleteMode) {
                        Icons.Default.DeleteOutline
                    } else {
                        Icons.Outlined.ChatBubbleOutline
                    },
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = text,
            color = DesignTextGray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

private fun renderBoldMarkdown(text: String) = buildAnnotatedString {

    val boldRegex = Regex("\\*\\*(.+?)\\*\\*")

    var lastIndex = 0

    boldRegex.findAll(text).forEach { match ->

        // Normal text before **...**
        append(
            text.substring(
                lastIndex,
                match.range.first
            )
        )

        // Text between ** and ** becomes bold
        withStyle(
            style = SpanStyle(
                fontWeight = FontWeight.Bold
            )
        ) {
            append(match.groupValues[1])
        }

        lastIndex = match.range.last + 1
    }

    // Remaining normal text
    if (lastIndex < text.length) {
        append(text.substring(lastIndex))
    }
}

@Composable
private fun StatusBanner(
    status: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Long answers look better left-aligned and slightly smaller.
    val isLongMessage = status.length > 200

    // Whenever a new answer/status appears, start at the top.
    LaunchedEffect(status) {
        scrollState.scrollTo(0)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 42.dp)
            .clickable { onDismiss() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = BorderStroke(
            1.dp,
            DesignPrimaryGreen.copy(alpha = 0.22f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()

                // IMPORTANT:
                // The answer box will never grow beyond this height.
                .heightIn(
                    max = if (isLongMessage) 500.dp else 220.dp
                )

                // If the answer is longer, the user can scroll.
                .verticalScroll(scrollState)

                .padding(
                    horizontal = 20.dp,
                    vertical = 18.dp
                )
        ) {
            Text(
                text = renderBoldMarkdown(status),
                color = Color.Black,

                // Slightly smaller for long answers
                fontSize = if (isLongMessage) {
                    16.sp
                } else {
                    18.sp
                },

                // Do NOT make the whole answer bold.
                // **...** portions are made bold by renderBoldMarkdown().
                fontWeight = FontWeight.Normal,

                // Long text is much easier to read left-aligned.
                textAlign = if (isLongMessage) {
                    TextAlign.Start
                } else {
                    TextAlign.Center
                },

                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WaveformMicMeter(
    level: Float,
    activeColor: Color,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 90)
    )

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = activeColor.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, activeColor.copy(alpha = 0.16f)),
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 18.dp)
        ) {
            val barCount = 34
            val gap = 5.dp.toPx()
            val barWidth = (size.width - gap * (barCount - 1)) / barCount
            val centerY = size.height / 2f
            val minBarHeight = 6.dp.toPx()

            for (i in 0 until barCount) {
                val centerDistance = abs(i - (barCount - 1) / 2f) / ((barCount - 1) / 2f)
                val centerBoost = 1f - centerDistance * 0.55f

                val waveVariation = 0.75f + 0.25f * sin(
                    i * 0.65 + animatedLevel * 8.0
                ).toFloat()

                val barHeight = (
                        minBarHeight +
                                animatedLevel * size.height * centerBoost * waveVariation
                        ).coerceIn(minBarHeight, size.height)

                val x = i * (barWidth + gap)

                drawRoundRect(
                    color = activeColor.copy(alpha = if (isRecording) 0.95f else 0.40f),
                    topLeft = Offset(x, centerY - barHeight / 2f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}
