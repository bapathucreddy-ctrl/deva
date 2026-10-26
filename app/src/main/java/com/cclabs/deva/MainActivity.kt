package com.cclabs.deva

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.*
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.*

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        requestNeededPermissions()
        setContent { MaterialTheme { DevaScreen() } }
    }

    private fun requestNeededPermissions() {
        val requested = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) requested += Manifest.permission.BLUETOOTH_CONNECT
        permissions.launch(requested.toTypedArray())
    }

    private fun startWake(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            return false
        }
        ContextCompat.startForegroundService(this, Intent(this, WakeService::class.java))
        return true
    }

    private fun stopWake() {
        stopService(Intent(this, WakeService::class.java))
    }

    private fun testVoiceCommand(onText: (String) -> Unit, onFinished: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            onText("Microphone permission is required.")
            onFinished()
            return
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(bundle: Bundle?) {
                val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    onText("I didn't catch that.")
                } else {
                    val result = AssistantActions(this@MainActivity).execute(CommandRouter.parse(text))
                    onText("Heard: $text\n${result.spoken}")
                    speak(result.spoken)
                }
                sr.destroy()
                onFinished()
            }
            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech matched. Speak after the listening prompt and retry."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Try speaking closer to the microphone."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Selected speech language is not supported."
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech language model is unavailable. Check Google/Samsung speech services or download the language model."
                    else -> "Speech recognition error $error"
                }
                onText(message)
                sr.destroy()
                onFinished()
            }
            override fun onReadyForSpeech(params: Bundle?) { onText("Ready — speak your command now") }
            override fun onBeginningOfSpeech() { onText("Hearing your voice…") }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        sr.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        )
    }

    private fun permissionSummary(): String {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val bluetooth = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return "Microphone: ${yesNo(mic)}  •  Notifications: ${yesNo(notifications)}  •  Bluetooth: ${yesNo(bluetooth)}"
    }

    private fun bluetoothSummary(): String {
        if (Build.VERSION.SDK_INT < 31) return "Bluetooth voice routing uses legacy Android behavior"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return "Bluetooth permission not granted"
        val devices = getSystemService(AudioManager::class.java).availableCommunicationDevices.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (devices.isEmpty()) return "No Bluetooth communication headset detected"
        return devices.joinToString { it.productName?.toString()?.ifBlank { "Bluetooth headset" } ?: "Bluetooth headset" }
    }

    private fun yesNo(value: Boolean) = if (value) "OK" else "Needed"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DevaScreen() {
        var status by remember { mutableStateOf("Ready") }
        var testing by remember { mutableStateOf(false) }
        var reminders by remember { mutableStateOf(ReminderStore(this).all()) }
        var serviceRunning by remember { mutableStateOf(false) }
        var serviceStatus by remember { mutableStateOf("Wake listener stopped") }
        var audioRoute by remember { mutableStateOf("Not active") }
        var permissionStatus by remember { mutableStateOf(permissionSummary()) }
        var bluetoothStatus by remember { mutableStateOf(bluetoothSummary()) }

        LaunchedEffect(Unit) {
            while (true) {
                val prefs = getSharedPreferences(WakeService.PREFS_RUNTIME, MODE_PRIVATE)
                serviceRunning = prefs.getBoolean(WakeService.KEY_RUNNING, false)
                serviceStatus = prefs.getString(WakeService.KEY_STATUS, "Wake listener stopped") ?: "Wake listener stopped"
                audioRoute = prefs.getString(WakeService.KEY_AUDIO_ROUTE, "Not active") ?: "Not active"
                permissionStatus = permissionSummary()
                bluetoothStatus = bluetoothSummary()
                reminders = ReminderStore(this@MainActivity).all()
                delay(1000)
            }
        }

        Scaffold(topBar = { TopAppBar(title = { Text("DEVA") }) }) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).padding(20.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Personal local voice assistant", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Status: $status")
                }

                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Wake listener", style = MaterialTheme.typography.titleMedium)
                            Text(if (serviceRunning) "Running" else "Stopped")
                            Text(serviceStatus, style = MaterialTheme.typography.bodyMedium)
                            Text("Audio: $audioRoute", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this@MainActivity))
                                    "Android on-device speech recognition is available"
                                else "Android on-device speech recognition is unavailable",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    status = if (startWake()) "Wake service requested" else "Microphone permission needed"
                                }) { Text("Start") }
                                OutlinedButton(onClick = {
                                    stopWake(); status = "Wake service stopped"
                                }) { Text("Stop") }
                                OutlinedButton(onClick = {
                                    ContextCompat.startForegroundService(
                                        this@MainActivity,
                                        Intent(this@MainActivity, WakeService::class.java)
                                            .setAction(WakeService.ACTION_TEST_GREETING)
                                    )
                                    status = "Testing Hi bro without the wake-word detector"
                                }) { Text("Test greeting") }
                            }
                        }
                    }
                }

                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Device readiness", style = MaterialTheme.typography.titleMedium)
                            Text(permissionStatus, style = MaterialTheme.typography.bodySmall)
                            Text("Bluetooth: $bluetoothStatus", style = MaterialTheme.typography.bodySmall)
                            Text("Supports phone, wired, USB and available wireless microphones", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item {
                    Button(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            stopWake() // Release the silent AudioRecord before the recognition test.
                            status = "Switching microphone to voice test…"
                            Handler(Looper.getMainLooper()).postDelayed({
                                testVoiceCommand(
                                    onText = { status = it },
                                    onFinished = { testing = false }
                                )
                            }, 1100L)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (testing) "Listening…" else "Test voice command") }
                }

                item { Text("Reminders", style = MaterialTheme.typography.titleMedium) }
                if (reminders.isEmpty()) {
                    item { Text("No pending reminders", style = MaterialTheme.typography.bodyMedium) }
                } else {
                    items(reminders, key = { it.id }) { reminder ->
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                Text(reminder.text)
                                Text(DateFormat.getDateTimeInstance().format(Date(reminder.whenMillis)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                item {
                    HorizontalDivider()
                    Text("V1 settings", style = MaterialTheme.typography.titleSmall)
                    Text("Spotify is preferred. YouTube Music is never selected automatically when Spotify cannot satisfy a request.", style = MaterialTheme.typography.bodySmall)
                    Text("DEVA uses local keyword detection for wake listening and Android speech recognition only after the greeting.", style = MaterialTheme.typography.bodySmall)
                    Text("After a reboot, Android requires DEVA to be opened/tapped before microphone foreground listening can restart.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun speak(text: String) {
        if (::tts.isInitialized) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "main")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.ENGLISH
    }

    override fun onDestroy() {
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
}
