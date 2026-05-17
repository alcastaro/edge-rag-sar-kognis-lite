package io.kognis.tactical.core.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Voice input agent — on-device speech-to-text tool.
 *
 * Wraps Android's [SpeechRecognizer] so the operator can dictate field queries
 * hands-free (helmet on, gloves on, low-light triage work). On modern Pixel /
 * Samsung / OnePlus devices the recognizer runs entirely on-device through
 * Google's offline recognition model — no network required, matching the rest
 * of Kognis Lite's zero-signal stance.
 *
 * Architectural role: a TOOL the operator invokes by tapping the mic button.
 * The transcript is then routed through [io.kognis.tactical.core.map.QueryPreprocessor]
 * exactly like typed input — so "marca un puesto médico en mi ubicación" by
 * voice produces the same agentic flow as typed text.
 */
class VoiceInputAgent(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onError: ((Int) -> Unit)? = null
    private var languageTag: String = "es-ES"

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun setLanguage(en: Boolean) {
        languageTag = if (en) "en-US" else "es-ES"
    }

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (Int) -> Unit,
    ) {
        if (!isAvailable()) {
            onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onError = onError

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) onPartial?.invoke(text)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) onFinal?.invoke(text)
            else onError?.invoke(SpeechRecognizer.ERROR_NO_MATCH)
            cancel()
        }
        override fun onError(error: Int) {
            Log.w(TAG, "Recognition error: $error")
            onError?.invoke(error)
            cancel()
        }
    }

    companion object {
        private const val TAG = "VoiceInputAgent"

        fun errorMessage(code: Int, en: Boolean): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> if (en) "Audio error" else "Error de audio"
            SpeechRecognizer.ERROR_CLIENT -> if (en) "Client error" else "Error de cliente"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (en) "Mic permission denied" else "Permiso de micrófono denegado"
            SpeechRecognizer.ERROR_NETWORK -> if (en) "Network error" else "Error de red"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (en) "Network timeout" else "Tiempo de red agotado"
            SpeechRecognizer.ERROR_NO_MATCH -> if (en) "No speech detected" else "No se detectó voz"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (en) "Recognizer busy" else "Reconocedor ocupado"
            SpeechRecognizer.ERROR_SERVER -> if (en) "Server error" else "Error del servidor"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (en) "Speech timeout" else "Tiempo de voz agotado"
            else -> if (en) "Voice error ($code)" else "Error de voz ($code)"
        }
    }
}
