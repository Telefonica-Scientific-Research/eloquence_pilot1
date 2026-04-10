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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.activity.viewModels
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
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
                    llmText?.let {
                        val calculatedDuration = (3000L + it.length * 80L).coerceIn(5000L, 30000L)
                        viewModel.updateStatus(it, durationMillis = calculatedDuration)
                    }
                }
                "com.example.wuw.CHUNK_SAVED" -> {
                    viewModel.incrementChunkAndShowStatus()
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

        setContent {
            WuwserviceappTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
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
    var showIndicesDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var newIndexName by remember { mutableStateOf("") }
    var chunkDuration by remember { mutableStateOf("10") }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Pilot 1 APP", style = MaterialTheme.typography.headlineMedium)
            
            // ✅ Only show if a conversation is actually active
            if (selectedIndex.isNotEmpty()) {
                Text(
                    text = "Current Conversation: $selectedIndex", 
                    color = Color.Blue, 
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(onClick = { viewModel.showConfigDialog.value = true }) {
                Text("Configurations")
            }
        }

        Button(
            onClick = { viewModel.clearHistory() },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) {
            Text("Clear History", color = Color.White)
        }

        // ✅ Configuration Dialog (LLM & Vector Store)
        if (showConfigDialog) {
            var expanded by remember { mutableStateOf(false) }
            val languages = listOf("en", "es", "it", "sr", "el")
            val scrollState = rememberScrollState()

            AlertDialog(
                onDismissRequest = { viewModel.showConfigDialog.value = false },
                modifier = Modifier.fillMaxWidth(0.9f).padding(vertical = 24.dp),
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                title = { Text("Configurations", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 8.dp)
                    ) {
                        Text("LLM & RAG Configurations", fontWeight = FontWeight.SemiBold, color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        TextField(value = viewModel.llmName.value, onValueChange = { viewModel.llmName.value = it }, label = { Text("LLM Name") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(value = viewModel.docsK.value, onValueChange = { viewModel.docsK.value = it }, label = { Text("docs_k") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(value = viewModel.temperature.value, onValueChange = { viewModel.temperature.value = it }, label = { Text("Temperature") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(value = viewModel.topP.value, onValueChange = { viewModel.topP.value = it }, label = { Text("top_p") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(value = viewModel.maxTokens.value, onValueChange = { viewModel.maxTokens.value = it }, label = { Text("max_tokens") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Language:", fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.width(100.dp)) { Text(viewModel.language.value) }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                languages.forEach { lang ->
                                    DropdownMenuItem(text = { Text(lang) }, onClick = { viewModel.language.value = lang; expanded = false })
                                }
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 16.dp))
                        
                        Text("Vector Store Configurations", fontWeight = FontWeight.SemiBold, color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        TextField(value = viewModel.chunkSize.value, onValueChange = { viewModel.chunkSize.value = it }, label = { Text("chunk_size") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(value = viewModel.embedName.value, onValueChange = { viewModel.embedName.value = it }, label = { Text("embed_name") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(value = viewModel.splittingStrategy.value, onValueChange = { viewModel.splittingStrategy.value = it }, label = { Text("splitting_strategy") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(value = viewModel.percentile.value, onValueChange = { viewModel.percentile.value = it }, label = { Text("percentile") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    Button(onClick = { 
                        viewModel.updateConfigurationsInService(context)
                        viewModel.showConfigDialog.value = false 
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.showConfigDialog.value = false }) { Text("Close") }
                }
            )
        }

        // ✅ Naming Dialog (Record Conversation) - Added Close Button
        if (showNamingDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissNamingDialog() },
                title = { Text("Configure Conversation") },
                text = {
                    Column {
                        Text("Conversation name:")
                        TextField(value = newIndexName, onValueChange = { newIndexName = it }, placeholder = { Text("Enter name...") })
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Chunk duration (seconds):")
                        TextField(value = chunkDuration, onValueChange = { chunkDuration = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (newIndexName.isNotBlank()) {
                            val duration = chunkDuration.toIntOrNull() ?: 10
                            viewModel.actualStartRecording(context, newIndexName, duration)
                            newIndexName = ""
                        }
                    }) { Text("Start Recording") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissNamingDialog() }) { Text("Close") }
                }
            )
        }

        // ✅ Change Conversation Dialog
        if (showIndicesDialog) {
            AlertDialog(
                onDismissRequest = { showIndicesDialog = false },
                modifier = Modifier.fillMaxWidth(0.9f),
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                title = { Text("Select Conversation Context") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        if (indices.isEmpty()) {
                            Text(
                                "No conversations found in Vector Store.", 
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                color = Color.Gray
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(indices) { index ->
                                    TextButton(
                                        onClick = { viewModel.selectIndex(context, index); showIndicesDialog = false },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(index, color = if (index == selectedIndex) Color.Blue else Color.Black, fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showIndicesDialog = false }) { Text("Close") }
                }
            )
        }

        // ✅ Delete Conversation Dialog
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                modifier = Modifier.fillMaxWidth(0.9f),
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                title = { Text("Select Conversation to delete:") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        if (indices.isEmpty()) {
                            Text(
                                "No conversations available to delete.", 
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                color = Color.Gray
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(indices) { index ->
                                    TextButton(
                                        onClick = { viewModel.deleteSelectedConversation(context, index); showDeleteDialog = false },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(index, color = Color.Red, fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("Close") }
                }
            )
        }

        // ✅ Bottom Buttons
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = { 
                viewModel.fetchAndShowIndices()
                showDeleteDialog = true 
            }) {
                Text("Delete Conversation")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { 
                viewModel.fetchAndShowIndices()
                showIndicesDialog = true 
            }) {
                Text("Change Conversation")
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { 
                if (!isRecording) {
                    viewModel.startConversationRecording()
                } else {
                    viewModel.stopConversationRecording(context)
                }
            }) {
                Text(if (!isRecording) "Record Conversation" else "Stop Recording")
            }
        }

        // ✅ Status Overlay - Centered Card
        if (status.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .clickable { viewModel.dismissStatus() },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Text(
                        text = status, 
                        fontSize = 22.sp, 
                        fontWeight = FontWeight.Bold, 
                        color = Color.White, 
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp)
                    )
                }
            }
        }
    }
}
