package com.sait.koserai.core

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Koser'in sesi. Temaya göre tonu değişir (Kızıl: daha kalın ve ağır). */
class Voice(ctx: Context, private val onSpeakStart: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var queued: Pair<String, () -> Unit>? = null
    private val callbacks = HashMap<String, () -> Unit>()
    private var seq = 0

    var hasTurkish = true
        private set
    private lateinit var tts: TextToSpeech

    init { tts = TextToSpeech(ctx.applicationContext) { status -> onInit(status) } }

    private fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            val r = tts.setLanguage(Locale("tr", "TR"))
            hasTurkish = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { onSpeakStart() } }
                override fun onDone(id: String?) { finish(id) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { finish(id) }
                override fun onError(id: String?, errorCode: Int) { finish(id) }
                override fun onStop(id: String?, interrupted: Boolean) { synchronized(callbacks) { callbacks.remove(id) } }
            })
            main.post { queued?.let { (t, d) -> queued = null; speak(t, d) } }
        }
    }

    private fun finish(id: String?) {
        val cb = synchronized(callbacks) { callbacks.remove(id) }
        if (cb != null) main.post(cb)
    }

    fun speak(text: String, done: () -> Unit) {
        if (text.isBlank()) { done(); return }
        if (!ready) { queued = text to done; return }
        val theme = Prefs.str("theme")
        val (pf, rf) = when (theme) { "kizil" -> 0.72 to 0.92; "altin" -> 1.12 to 1.0; else -> 0.95 to 1.02 }
        tts.setPitch((Prefs.dbl("ttsPitch") * pf).toFloat().coerceIn(0.3f, 2f))
        tts.setSpeechRate((Prefs.dbl("ttsRate") * rf).toFloat().coerceIn(0.4f, 2.5f))
        val id = "u${++seq}"
        synchronized(callbacks) { callbacks[id] = done }
        val clean = text.replace(Regex("[*#_`]"), "")
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun stop() {
        synchronized(callbacks) { callbacks.clear() }
        if (ready) tts.stop()
    }

    val speaking get() = ready && tts.isSpeaking

    fun shutdown() { tts.shutdown() }
}
