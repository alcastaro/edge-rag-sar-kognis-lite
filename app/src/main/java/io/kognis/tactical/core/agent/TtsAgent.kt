package io.kognis.tactical.core.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Text-to-speech agent — closes the hands-free agentic loop.
 *
 * Voice in (`VoiceInputAgent`) → Gemma 4 reasoning → speech out (this class).
 * Lets a responder operate gloves-on, eyes-on-the-patient without ever
 * looking at the screen.
 *
 * Uses Android's bundled `TextToSpeech` engine (Google or Samsung depending on
 * device). On modern Pixel / S24 Ultra, an on-device neural TTS model is
 * preferred — zero network. The init callback waits for the engine to load
 * before any speech requests are honored.
 *
 * Architectural role: a TOOL on the output side of the agent loop. Toggleable
 * by the operator (icon in the chat toolbar).
 */
class TtsAgent(private val context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile var ready: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var isSpeaking: Boolean = false
        private set
    @Volatile private var pendingLocale: Locale = Locale("es", "ES")

    fun init(onReady: (Boolean) -> Unit = {}) {
        if (ready) { onReady(true); return }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(pendingLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fallback to US English
                    tts?.setLanguage(Locale.US)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { isSpeaking = true }
                    override fun onDone(id: String?) { isSpeaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { isSpeaking = false }
                })
                ready = true
                Log.i(TAG, "TTS ready (locale=$pendingLocale)")
                onReady(true)
            } else {
                lastError = "TTS init failed (status=$status)"
                Log.e(TAG, lastError ?: "init fail")
                onReady(false)
            }
        }
    }

    fun setLanguage(en: Boolean) {
        pendingLocale = if (en) Locale.US else Locale("es", "ES")
        if (ready) {
            val r = tts?.setLanguage(pendingLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
        }
    }

    /** Speak [text]. Truncates very long responses (TTS quality degrades past ~3kb). */
    fun speak(text: String) {
        if (!ready) {
            Log.w(TAG, "speak() called before TTS ready — dropping")
            return
        }
        val clean = sanitize(text).take(2_000)
        if (clean.isBlank()) return
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "kognis_tts_${System.currentTimeMillis()}")
    }

    fun stop() {
        runCatching { tts?.stop() }
        isSpeaking = false
    }

    /** Toggle: if currently speaking, stop. Otherwise speak [text]. */
    fun toggleSpeak(text: String) {
        if (isSpeaking) stop() else speak(text)
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun sanitize(text: String): String {
        // Strip LOCATION_JSON tail and other structured output before speaking.
        return text
            .replace(Regex("""(?i)LOCATION_JSON\s*:\s*\{[^{}]*\}"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    companion object {
        private const val TAG = "TtsAgent"
    }
}
