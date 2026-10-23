package com.cclabs.deva

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.Locale

class WakeService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private enum class State { WAITING_FOR_WAKE, WAITING_FOR_COMMAND, SPEAKING, STOPPED }

    private var recognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private val handler = Handler(Looper.getMainLooper())
    private var state = State.WAITING_FOR_WAKE
    private var nextStateAfterSpeech = State.WAITING_FOR_WAKE
    private var recognizerReady = false
    private var awaitingYouTubeConfirmation = false
    private var usingOnDeviceRecognizer = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        setRuntimeState(true, "Starting local listener", "Checking audio route")
        startForeground(NOTIFICATION_ID, notification("Starting local listener"))
        tts = TextToSpeech(this, this)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update("Microphone permission missing")
            stopSelf()
            return
        }

        val route = routeCommunicationAudio()
        setRuntimeState(true, "Listening for Hey Deva", route)

        createPreferredRecognizer()
        startListening(350)
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            state = State.STOPPED
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createPreferredRecognizer() {
        recognizer?.destroy()
        usingOnDeviceRecognizer = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        recognizer = if (usingOnDeviceRecognizer) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer?.setRecognitionListener(this)
        recognizerReady = true
        update(if (usingOnDeviceRecognizer) "Listening locally for Hey Deva" else "Listening for Hey Deva")
    }

    private fun switchToSystemRecognizer() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        usingOnDeviceRecognizer = false
        recognizerReady = true
        update("Local speech model unavailable — using Android speech service")
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, usingOnDeviceRecognizer)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

    private fun startListening(delayMs: Long = 0) {
        if (!recognizerReady || state == State.SPEAKING || state == State.STOPPED) return
        handler.postDelayed({
            if (state == State.SPEAKING || state == State.STOPPED) return@postDelayed
            try { recognizer?.startListening(recognizerIntent()) } catch (_: Exception) { retry(900) }
        }, delayMs)
    }

    override fun onPartialResults(bundle: Bundle?) {
        if (state != State.WAITING_FOR_WAKE) return
        val text = firstResult(bundle)
        if (containsWakePhrase(text)) update("Hey Deva heard — waiting for the rest")
    }

    override fun onResults(bundle: Bundle?) {
        val text = firstResult(bundle).trim()
        when (state) {
            State.WAITING_FOR_WAKE -> handleWakeResult(text)
            State.WAITING_FOR_COMMAND -> handleCommand(text)
            State.SPEAKING, State.STOPPED -> Unit
        }
    }

    private fun handleWakeResult(text: String) {
        val inlineCommand = commandAfterWake(text)
        when {
            inlineCommand != null && inlineCommand.isNotBlank() -> handleCommand(inlineCommand)
            containsWakePhrase(text) -> {
                update("Awake — listening for command")
                speak("Hi Chetan", "wake_prompt", State.WAITING_FOR_COMMAND)
            }
            else -> retry(250)
        }
    }

    private fun handleCommand(text: String) {
        if (text.isBlank()) {
            awaitingYouTubeConfirmation = false
            update("Listening for Hey Deva")
            speak("I didn't catch that.", "empty_command", State.WAITING_FOR_WAKE)
            return
        }

        if (awaitingYouTubeConfirmation) {
            awaitingYouTubeConfirmation = false
            val normalized = text.trim().lowercase(Locale.ENGLISH)
            val result = if (normalized in setOf("yes","yeah","yep","sure","okay","ok")) {
                AssistantActions(this).openYouTubeMusic()
            } else {
                AssistantActions.Result("Okay, staying with Spotify.", true)
            }
            update(result.spoken)
            speak(result.spoken, "youtube_confirmation", State.WAITING_FOR_WAKE)
            return
        }

        val result = AssistantActions(this).execute(CommandRouter.parse(text))
        awaitingYouTubeConfirmation = result.followUp == AssistantActions.FollowUp.CONFIRM_YOUTUBE_MUSIC
        update(result.spoken)
        speak(result.spoken, "command_reply", if (awaitingYouTubeConfirmation) State.WAITING_FOR_COMMAND else State.WAITING_FOR_WAKE)
    }

    private fun speak(text: String, id: String, after: State) {
        nextStateAfterSpeech = after
        if (!::tts.isInitialized) {
            state = after
            startListening(300)
            return
        }
        try { recognizer?.cancel() } catch (_: Exception) {}
        state = State.SPEAKING
        val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (queued == TextToSpeech.ERROR) {
            state = after
            startListening(350)
        }
    }

    override fun onError(error: Int) {
        if (state == State.SPEAKING || state == State.STOPPED) return
        if (usingOnDeviceRecognizer &&
            (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
             error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
            switchToSystemRecognizer()
            startListening(300)
            return
        }
        val delay = when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1800L
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 1800L
            else -> 1200L
        }
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            update("Speech recognizer error $error — retrying")
        }
        retry(delay)
    }

    private fun retry(delay: Long) = startListening(delay)
    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    private fun containsWakePhrase(text: String) = WAKE_REGEX.containsMatchIn(text)
    private fun commandAfterWake(text: String): String? {
        val match = WAKE_REGEX.find(text) ?: return null
        return text.substring(match.range.last + 1).trim().trimStart(',', '.', '-', ':')
    }

    private fun routeCommunicationAudio(): String {
        if (Build.VERSION.SDK_INT < 31) return "Phone microphone"
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "Phone microphone (Bluetooth permission missing)"
        }
        val am = getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val candidate = am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
        if (candidate != null && am.setCommunicationDevice(candidate)) {
            val label = candidate.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth headset"
            return "$label microphone requested"
        }
        return "Phone microphone (no Bluetooth communication route)"
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 2, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DEVA wake listener")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun update(text: String) {
        setRuntimeState(true, text, getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE).getString(KEY_AUDIO_ROUTE, "Unknown") ?: "Unknown")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun setRuntimeState(running: Boolean, status: String, route: String) {
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, running)
            .putString(KEY_STATUS, status)
            .putString(KEY_AUDIO_ROUTE, route)
            .apply()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "DEVA wake service", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) = finishSpeech()
                override fun onDone(utteranceId: String?) = finishSpeech()
            })
        }
    }

    private fun finishSpeech() {
        handler.post {
            if (state == State.STOPPED) return@post
            state = nextStateAfterSpeech
            startListening(300)
        }
    }

    override fun onDestroy() {
        state = State.STOPPED
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
        recognizer?.destroy()
        if (::tts.isInitialized) tts.shutdown()
        if (Build.VERSION.SDK_INT >= 31) getSystemService(AudioManager::class.java).clearCommunicationDevice()
        setRuntimeState(false, "Wake listener stopped", "Not active")
        super.onDestroy()
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        private const val ACTION_STOP = "com.cclabs.deva.STOP_WAKE"
        private const val CHANNEL_SERVICE = "service"
        private const val NOTIFICATION_ID = 1
        const val PREFS_RUNTIME = "deva_runtime"
        const val KEY_RUNNING = "wake_running"
        const val KEY_STATUS = "wake_status"
        const val KEY_AUDIO_ROUTE = "audio_route"
        private val WAKE_REGEX = Regex("\\bhey\\s+deva\\b", RegexOption.IGNORE_CASE)
    }
}
