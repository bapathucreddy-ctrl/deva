package com.cclabs.deva

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getKeywordsFile
import com.k2fsa.sherpa.onnx.getKwsModelConfig
import java.util.Locale
import kotlin.concurrent.thread

class WakeService : Service(), TextToSpeech.OnInitListener {
    private enum class State { INITIALIZING, WAITING_FOR_WAKE, GREETING, WAITING_FOR_COMMAND, SPEAKING, STOPPED }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private lateinit var kws: KeywordSpotter
    private var kwsReady = false
    private var kwsThread: Thread? = null
    @Volatile private var kwsRunning = false
    private var audioRecord: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null
    private var commandRecognizer: SpeechRecognizer? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var preferredMic: AudioDeviceInfo? = null
    private var currentRouteKey = ""
    private val routeRefresh = Runnable {
        if (state != State.STOPPED) {
            val before = currentRouteKey
            val route = routeCommunicationAudio()
            if (before != currentRouteKey && state == State.WAITING_FOR_WAKE && kwsReady) {
                stopWakeAudio()
                setRuntimeState(true, "Switching microphone", route)
                handler.postDelayed({ startSilentWakeListening() }, 450)
            }
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            handler.removeCallbacks(routeRefresh)
            handler.postDelayed(routeRefresh, 650)
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handler.removeCallbacks(routeRefresh)
            handler.postDelayed(routeRefresh, 650)
        }
    }
    @Volatile private var state = State.INITIALIZING
    private var nextStateAfterSpeech = State.WAITING_FOR_WAKE

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing silent wake listener"))
        tts = TextToSpeech(this, this)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update("Microphone permission missing")
            stopSelf()
            return
        }

        val route = routeCommunicationAudio()
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        setRuntimeState(true, "Loading Hey Deva wake model", route)

        thread(name = "deva-kws-init", isDaemon = true) {
            try {
                val config = KeywordSpotterConfig(
                    featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = getKwsModelConfig(type = 1)!!,
                    keywordsFile = getKeywordsFile(type = 1),
                    keywordsScore = 2.0f,
                    keywordsThreshold = 0.12f,
                    numTrailingBlanks = 1
                )
                kws = KeywordSpotter(application.assets, config)
                kwsReady = true
                handler.post { startSilentWakeListening() }
            } catch (e: Throwable) {
                handler.post {
                    update("Wake model failed to load")
                    stopSelf()
                }
            }
        }
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

    private fun startSilentWakeListening() {
        if (!kwsReady || state == State.STOPPED || kwsRunning) return
        stopCommandRecognizer()
        state = State.WAITING_FOR_WAKE
        val route = routeCommunicationAudio()
        setRuntimeState(true, "Silently listening for Hey Deva", route)
        update("Silently listening for Hey Deva")

        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            update("Microphone initialization failed")
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBytes * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            update("Microphone initialization failed")
            return
        }

        preferredMic?.let { mic -> record.setPreferredDevice(mic) }
        audioRecord = record
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(record.audioSessionId)?.also { it.enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true }
            }
        } catch (_: Throwable) {}

        val stream = kws.createStream()
        if (stream.ptr == 0L) {
            releaseWakeAudio()
            update("Wake detector stream failed")
            return
        }

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                update("Microphone could not start recording")
                stream.release()
                releaseWakeAudio()
                return
            }
        } catch (e: Exception) {
            update("Microphone start failed")
            stream.release()
            releaseWakeAudio()
            return
        }
        kwsRunning = true
        update("Microphone recording started — testing live signal")

        kwsThread = thread(name = "deva-kws-audio", isDaemon = true) {
            processWakeAudio(stream)
        }
    }

    private fun processWakeAudio(stream: OnlineStream) {
        val buffer = ShortArray(1600)
        var audioReads = 0
        try {
            while (kwsRunning && state == State.WAITING_FOR_WAKE) {
                val count = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (count < 0) {
                    handler.post { update("Wake microphone read error " + count) }
                    break
                }
                if (count == 0) continue
                audioReads++
                if (audioReads % 20 == 0) {
                    var power = 0.0
                    for (i in 0 until count) power += buffer[i].toDouble() * buffer[i].toDouble()
                    val rms = kotlin.math.sqrt(power / count) / 32768.0
                    val db = (20.0 * kotlin.math.log10(rms.coerceAtLeast(0.000001))).toInt()
                    handler.post {
                        if (state == State.WAITING_FOR_WAKE) {
                            update("Wake microphone LIVE " + db + " dBFS - say Hey Deva")
                        }
                    }
                }

                val samples = FloatArray(count) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)

                while (kwsRunning && kws.isReady(stream)) {
                    kws.decode(stream)
                    val keyword = kws.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        kws.reset(stream)
                        handler.post { onWakeDetected() }
                        return
                    }
                }
            }
        } catch (e: Throwable) {
            if (state != State.STOPPED) handler.post { update("Wake engine error: " + e.javaClass.simpleName) }
        } finally {
            stream.release()
        }
    }

    private fun onWakeDetected() {
        if (state != State.WAITING_FOR_WAKE) return
        state = State.GREETING
        stopWakeAudio()
        update("Hey Deva detected")
        speak("Hi Chetan", "wake_greeting", State.WAITING_FOR_COMMAND)
    }

    private fun startCommandRecognizer() {
        if (state == State.STOPPED) return
        stopWakeAudio()
        stopCommandRecognizer()
        state = State.WAITING_FOR_COMMAND
        update("Listening for your command")

        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        commandRecognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(bundle: Bundle?) {
                val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                stopCommandRecognizer()
                if (text.isBlank()) {
                    speak("I didn't catch that.", "empty_command", State.WAITING_FOR_WAKE)
                    return
                }
                val result = AssistantActions(this@WakeService).execute(CommandRouter.parse(text))
                update("Heard: $text")
                speak(result.spoken, "command_reply", State.WAITING_FOR_WAKE)
            }

            override fun onError(error: Int) {
                stopCommandRecognizer()
                if (state == State.STOPPED) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> speak("I didn't catch that.", "no_match", State.WAITING_FOR_WAKE)
                    else -> {
                        update("Command recognition error $error")
                        speak("I couldn't hear the command clearly.", "command_error", State.WAITING_FOR_WAKE)
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

        try {
            sr.startListening(intent)
        } catch (_: Throwable) {
            stopCommandRecognizer()
            restartWakeAfter(500)
        }
    }

    private fun speak(text: String, id: String, after: State) {
        nextStateAfterSpeech = after
        state = State.SPEAKING
        val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (queued == TextToSpeech.ERROR) finishSpeech()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    handler.post { finishSpeech() }
                }
                override fun onError(utteranceId: String?) {
                    handler.post { finishSpeech() }
                }
            })
        }
    }

    private fun finishSpeech() {
        if (state == State.STOPPED) return
        state = nextStateAfterSpeech
        when (state) {
            State.WAITING_FOR_COMMAND -> startCommandRecognizer()
            State.WAITING_FOR_WAKE -> restartWakeAfter(350)
            else -> Unit
        }
    }

    private fun restartWakeAfter(delayMs: Long) {
        if (state == State.STOPPED) return
        handler.postDelayed({
            if (state != State.STOPPED) startSilentWakeListening()
        }, delayMs)
    }

    private fun stopWakeAudio() {
        kwsRunning = false
        releaseWakeAudio()
    }

    private fun releaseWakeAudio() {
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { agc?.release() } catch (_: Throwable) {}
        try { ns?.release() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        agc = null
        ns = null
        audioRecord = null
    }

    private fun stopCommandRecognizer() {
        try { commandRecognizer?.cancel() } catch (_: Throwable) {}
        try { commandRecognizer?.destroy() } catch (_: Throwable) {}
        commandRecognizer = null
    }

    private fun routeCommunicationAudio(): String {
        // Idle wake listening must NOT use call/communication mode.
        // Bluetooth SCO / BLE communication routing can hijack the media stream,
        // degrade music quality, and change the volume controls.
        // Prefer wired/USB microphones; otherwise use the phone microphone.
        // AudioRecord input selection does not change the media output route.
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val selected = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        } ?: inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        }
        preferredMic = selected
        currentRouteKey = selected?.id?.toString() ?: "default"
        return when (selected?.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset mic (media unchanged)"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB mic (media unchanged)"
            else -> "Phone mic (wireless media unchanged)"
        }
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DEVA")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun update(text: String) {
        val prefs = getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE)
        setRuntimeState(true, text, prefs.getString(KEY_AUDIO_ROUTE, "Unknown") ?: "Unknown")
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

    override fun onDestroy() {
        state = State.STOPPED
        handler.removeCallbacksAndMessages(null)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        stopWakeAudio()
        stopCommandRecognizer()
        if (::kws.isInitialized) kws.release()
        if (::tts.isInitialized) tts.shutdown()
        setRuntimeState(false, "Wake listener stopped", "Not active")
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.cclabs.deva.STOP_WAKE"
        private const val CHANNEL_SERVICE = "service"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000
        const val PREFS_RUNTIME = "deva_runtime"
        const val KEY_RUNNING = "wake_running"
        const val KEY_STATUS = "wake_status"
        const val KEY_AUDIO_ROUTE = "audio_route"
    }
}
