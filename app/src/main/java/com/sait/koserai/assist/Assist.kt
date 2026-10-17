package com.sait.koserai.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.sait.koserai.MainActivity
import com.sait.koserai.core.Prefs
import com.sait.koserai.wake.WakeWordService

/** Telefonun "Dijital asistan uygulaması" olarak seçilebilmesi için. Uzun basınca / köşeden kaydırınca Koser açılır. */
class KoserVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
        if (Prefs.bool("wakeEnabled")) WakeWordService.tryStart(this)
    }

    override fun onShutdown() {
        instance = null
        super.onShutdown()
    }

    companion object {
        @Volatile var instance: KoserVoiceService? = null

        /** Uyandırma kelimesi duyulunca: asistan oturumunu sistem üzerinden aç (arka plandan açılmaya izin verilen yol). */
        fun summon(): Boolean {
            val s = instance ?: return false
            return try {
                s.showSession(Bundle().apply { putBoolean("wake", true) }, VoiceInteractionSession.SHOW_WITH_ASSIST)
                true
            } catch (e: Exception) { false }
        }

        fun isDefaultAssistant(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT))
                    return rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
            }
            val cur = Settings.Secure.getString(ctx.contentResolver, "voice_interaction_service") ?: ""
            return cur.startsWith(ctx.packageName + "/")
        }
    }
}

class KoserSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = KoserSession(this)
}

class KoserSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        current = this
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
        try {
            if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(i) else context.startActivity(i)
        } catch (e: Exception) {
            try { context.startActivity(i) } catch (_: Exception) {}
        }
    }

    override fun onHide() { if (current === this) current = null; super.onHide() }

    companion object {
        @Volatile var current: KoserSession? = null
        fun close() { try { current?.hide() } catch (_: Exception) {} }
    }
}

/**
 * Asistan seçilince sistem konuşma tanıma servisini de bu uygulamaya bağlar.
 * Diğer uygulamaların sesle yazma özelliği bozulmasın diye istekleri telefonun asıl tanıma servisine (Google) aktarırız.
 */
class KoserRecognitionService : RecognitionService() {
    private var rec: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val cb = listener ?: return
        rec?.destroy()
        val comp = Speech.bestRecognizer(this)
        if (comp == null) { cb.error(SpeechRecognizer.ERROR_CLIENT); return }
        rec = SpeechRecognizer.createSpeechRecognizer(this, comp).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { cb.readyForSpeech(params ?: Bundle()) }
                override fun onBeginningOfSpeech() { cb.beginningOfSpeech() }
                override fun onRmsChanged(rmsdB: Float) { cb.rmsChanged(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) { buffer?.let { cb.bufferReceived(it) } }
                override fun onEndOfSpeech() { cb.endOfSpeech() }
                override fun onError(error: Int) { cb.error(error) }
                override fun onResults(results: Bundle?) { cb.results(results ?: Bundle()) }
                override fun onPartialResults(partialResults: Bundle?) { cb.partialResults(partialResults ?: Bundle()) }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(recognizerIntent ?: Intent())
        }
    }

    override fun onStopListening(listener: Callback?) { rec?.stopListening() }
    override fun onCancel(listener: Callback?) { rec?.cancel() }
    override fun onDestroy() { rec?.destroy(); rec = null; super.onDestroy() }
}

object Speech {
    /** Telefondaki gerçek konuşma tanıma servisini bul (kendimiz hariç), Google'ı tercih et. */
    fun bestRecognizer(ctx: Context): ComponentName? {
        val list = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != ctx.packageName }
        return list.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: list.firstOrNull { it.packageName == "com.google.android.tts" }
            ?: list.firstOrNull { it.packageName.startsWith("com.google") }
            ?: list.firstOrNull()
    }
}
