package com.example.wuw_service_app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.wuw_service_app.service.wuw.AudioService
import com.example.wuw_service_app.ui.theme.WuwserviceappTheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WuwserviceappTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Greeting()
                    Button(onClick = {
                        startService(Intent(this, AudioService::class.java))
                    }) {
                        Text(text = "Start Audio Service")
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(modifier: Modifier = Modifier) {
    Text(
            text="Sample app with composed view",
            modifier = modifier
    )
}

@Composable
fun SimpleButton(context: Context) {

}

// To show the preview
@Preview(showBackground=true)
@Composable
fun GreetingPreview() {
    WuwserviceappTheme {
        Greeting()
    }
}