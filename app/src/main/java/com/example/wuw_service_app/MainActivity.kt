package com.example.wuw_service_app

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.wuw_service_app.service.wuw.AudioService
import com.example.wuw_service_app.ui.theme.WuwserviceappTheme



class MainActivity : ComponentActivity() {

    private var logTag = "Main"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkRecordAudioPermission()

        setContent {
            WuwserviceappTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Greeting()
                    StartButton()
                }
            }
        }

        startAudioService()
    }

    private fun startAudioService(){
        val serviceIntent = Intent(this@MainActivity, AudioService::class.java)
        startService(serviceIntent)
    }

    private fun checkRecordAudioPermission() {
        // Check whether the app is running on a device that has a microphone.
        if (applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            // Request the RECORD_AUDIO permission.
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                showAlertDialog(
                    "Permiso de audio",
                    "Esta aplicación necesita acceso al micrófono para poder entenderte.",
                    "Entendido",
                    Manifest.permission.RECORD_AUDIO
                )
            } else {
                //Log.d(LOG_TAG, "Record audio permission granted")
            }
        } else {
            // Gracefully degrade your app experience.
            // Create a dialog informing the user that the device does not have a microphone.
            showAlertDialog(
                "Aviso",
                "Este dispositivo no tiene micrófono, por lo que no podrás hablar con él.",
                "Vale",
                null
            )
        }
    }

    private fun showAlertDialog(
        title: String,
        message: String,
        okButton: String,
        requestPermission: String?
    ) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(okButton) { _, _ ->
                if (requestPermission != null) {
                    requestPermissions(arrayOf(requestPermission), 1)
                }
            }
        val alert = builder.create()
        alert.show()
    }

}

@Composable
fun Greeting(modifier: Modifier = Modifier) {
    Text(
            text="WuW Service app",
            modifier = modifier
    )
}

@Composable
fun StartButton(){
    Column(
        Modifier
            .fillMaxWidth()
            .absolutePadding(10.dp, 100.dp, 10.dp, 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = {
                TODO("Do something!")
            },
            colors = ButtonDefaults.buttonColors(Color(0XFF0F9D58)),
        ) {
            Text("Start button", color = Color.White)
        }
    }
}

// To show the preview
@Preview(showBackground=true)
@Composable
fun GreetingPreview() {
    WuwserviceappTheme {
        Greeting()
    }
}

