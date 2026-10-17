package com.sait.koserai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import com.sait.koserai.access.KoserAccessibilityService
import com.sait.koserai.assist.KoserSession
import com.sait.koserai.assist.KoserVoiceService
import com.sait.koserai.assist.Speech
import com.sait.koserai.core.AiClient
import com.sait.koserai.core.Brain
import com.sait.koserai.core.Prefs
import com.sait.koserai.core.Reply
import com.sait.koserai.core.Voice
import com.sait.koserai.tv.TvController
import com.sait.koserai.wake.VoskModel
import com.sait.koserai.wake.WakeTrainer
import com.sait.koserai.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var voice: Voice
    private lateinit var brain: Brain
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var state = "idle"
    private var jsReady = false
    private var listenOnReady = false
    private var panelOpen = false
    private var trainer: WakeTrainer? = null
    private var pendingPerm: String? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val name = pendingPerm; pendingPerm = null
        val denied = res.filterValues { !it }.keys
        if (denied.isNotEmpty() && denied.none { shouldShowRequestPermissionRationale(it) }) {
            // "Bir daha sorma" seçilmiş: uygulama ayarlarını aç
            emit(JSONObject().put("type", "toast").put("text", "İzni uygulama ayarlarından açman gerekiyor."))
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        pushPerms()
        if (name == "mic" && res[Manifest.permission.RECORD_AUDIO] == true && listenOnReady) startListening()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        instance = this

        voice = Voice(this) { setState("speaking") }
        brain = Brain(this) { r -> deliver(r, speakIt = true) }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this).apply {
            setBackgroundColor(0xFF03070C.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.host == "appassets.androidplatform.net") return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "KoserNative")
            loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        }
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (panelOpen) emit(JSONObject().put("type", "back"))
                else { stopAll(); moveTaskToBack(true) }
            }
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val listen = i?.getBooleanExtra(EXTRA_LISTEN, false) == true ||
            i?.action == Intent.ACTION_ASSIST || i?.action == Intent.ACTION_VOICE_COMMAND
        i?.removeExtra(EXTRA_LISTEN)
        if (listen) { if (jsReady) main.postDelayed({ startListening() }, 250) else listenOnReady = true }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        KoserSession.close()
        if (jsReady) pushPerms()
    }

    override fun onPause() {
        isForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        instance = null
        recognizer?.destroy()
        voice.shutdown()
        trainer?.cancel()
        scope.cancel()
        WakeWordService.resume()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ JS iletişimi

    private fun emit(o: JSONObject) {
        val js = "window.Koser && window.Koser.onNative(" + JSONObject.quote(o.toString()) + ")"
        main.post { if (::web.isInitialized) web.evaluateJavascript(js, null) }
    }

    private fun setState(s: String) {
        state = s
        emit(JSONObject().put("type", "state").put("value", s))
    }

    private fun pushPerms() = emit(JSONObject().put("type", "perms").put("data", permStatus()))

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun permStatus(): JSONObject {
        val pm = getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("mic", granted(Manifest.permission.RECORD_AUDIO))
            .put("contacts", granted(Manifest.permission.READ_CONTACTS))
            .put("phone", granted(Manifest.permission.CALL_PHONE))
            .put("notif", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
            .put("overlay", Settings.canDrawOverlays(this))
            .put("access", KoserAccessibilityService.isEnabled(this))
            .put("assistant", KoserVoiceService.isDefaultAssistant(this))
            .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
            .put("model", VoskModel.isReady(this))
            .put("modelDownloading", VoskModel.downloading)
            .put("wakeRunning", WakeWordService.running)
            .put("recognizer", Speech.bestRecognizer(this) != null)
            .put("tts", voice.hasTurkish)
    }

    private fun requestPerm(name: String) {
        val perms = when (name) {
            "mic" -> arrayOf(Manifest.permission.RECORD_AUDIO)
            "contacts" -> arrayOf(Manifest.permission.READ_CONTACTS)
            "phone" -> arrayOf(Manifest.permission.CALL_PHONE)
            "notif" -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else return
            "overlay" -> { safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
            "access" -> { safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
            "battery" -> { safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); return }
            "assistant" -> {
                if (!safeStart(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)))
                    safeStart(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                return
            }
            else -> return
        }
        pendingPerm = name
        permLauncher.launch(perms)
    }

    private fun safeStart(i: Intent): Boolean = try { startActivity(i); true } catch (e: Exception) { false }

    // ------------------------------------------------------------------ dinleme

    fun startListening() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) { listenOnReady = true; requestPerm("mic"); return }
        listenOnReady = false
        voice.stop()
        WakeWordService.pause()
        recognizer?.destroy()
        val comp = Speech.bestRecognizer(this)
        val r = if (comp != null) SpeechRecognizer.createSpeechRecognizer(this, comp) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setState("listening") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { emit(JSONObject().put("type", "level").put("v", ((rmsdB + 2) / 12f).coerceIn(0f, 1f).toDouble())) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setState("thinking") }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        emit(JSONObject().put("type", "hint").put("text", "Bir şey duyamadım."))
                        goIdle()
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> main.postDelayed({ startListening() }, 400)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { goIdle(); requestPerm("mic") }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        deliver(Reply("İnternet bağlantısı yok gibi görünüyor."), true)
                    }
                    else -> { emit(JSONObject().put("type", "hint").put("text", "Ses tanıma hatası ($error).")); goIdle() }
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) { goIdle(); return }
                process(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                emit(JSONObject().put("type", "partial").put("text", t))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        setState("listening")
        try { r.startListening(i) } catch (e: Exception) { deliver(Reply("Ses tanıma başlatılamadı."), true) }
    }

    private fun process(text: String) {
        emit(JSONObject().put("type", "user").put("text", text))
        setState("thinking")
        scope.launch {
            val reply = try { brain.handle(text) } catch (e: Exception) { Reply("Bir hata oluştu.") }
            deliver(reply, true)
        }
    }

    private fun deliver(r: Reply, speakIt: Boolean) {
        r.ui?.let { ui ->
            when (ui.optString("type")) {
                "perm" -> main.postDelayed({ requestPerm(ui.optString("name")) }, 1500)
                "stop" -> { stopAll(); emit(ui); return }
            }
            emit(ui)
        }
        if (r.say.isNotBlank()) emit(JSONObject().put("type", "koser").put("text", r.say))
        if (!speakIt || r.say.isBlank()) { goIdle(); return }
        WakeWordService.pause()
        voice.speak(r.say) {
            if (r.listenAgain) startListening() else goIdle()
        }
    }

    private fun goIdle() {
        setState("idle")
        WakeWordService.resume()
    }

    private fun stopAll() {
        recognizer?.cancel()
        voice.stop()
        brain.cancelPending()
        goIdle()
    }

    // ------------------------------------------------------------------ köprü (web arayüzü -> Kotlin)

    inner class Bridge {
        private fun ui(block: () -> Unit) = main.post(block)

        @JavascriptInterface fun ready() = ui {
            jsReady = true
            emit(JSONObject().put("type", "settings").put("data", Prefs.all()))
            pushPerms()
            if (listenOnReady) main.postDelayed({ startListening() }, 300)
            else if (Prefs.bool("onboarded")) {
                // ilk açılışta selam ver
                if (!greeted) { greeted = true; emit(JSONObject().put("type", "koser").put("text", Brain.greeting())) }
            }
            if (Prefs.bool("wakeEnabled") && !WakeWordService.running) WakeWordService.tryStart(this@MainActivity)
        }

        @JavascriptInterface fun listen() = ui { if (state == "listening") stopAll() else startListening() }
        @JavascriptInterface fun stop() = ui { stopAll() }
        @JavascriptInterface fun sendText(t: String) = ui { if (t.isNotBlank()) { voice.stop(); recognizer?.cancel(); process(t) } }
        @JavascriptInterface fun getSettings(): String = Prefs.all().toString()
        @JavascriptInterface fun saveSettings(json: String): String {
            val err = try { Prefs.merge(JSONObject(json)) } catch (e: Exception) { "Ayar kaydedilemedi." }
            if (err == null) {
                val o = JSONObject(json)
                if (o.has("geminiKey") || o.has("geminiModel") || o.has("userName")) AiClient.reset()
                if (o.has("tvIp") || o.has("tvType")) { Prefs.clearPins(); Prefs.put("tvToken", ""); Prefs.put("lgClientKey", "") }
            }
            return err ?: ""
        }
        @JavascriptInterface fun permStatus(): String = this@MainActivity.permStatus().toString()
        @JavascriptInterface fun requestPerm(name: String) = ui { this@MainActivity.requestPerm(name) }

        @JavascriptInterface fun downloadModel() = ui {
            VoskModel.download(this@MainActivity,
                progress = { p -> emit(JSONObject().put("type", "model").put("progress", p)) },
                done = { err ->
                    emit(JSONObject().put("type", "model").put("done", true).put("error", err ?: ""))
                    main.post { pushPerms() }
                })
            pushPerms()
        }
        @JavascriptInterface fun deleteModel() = ui {
            Prefs.put("wakeEnabled", false); WakeWordService.stop(this@MainActivity)
            VoskModel.delete(this@MainActivity); pushPerms()
        }
        @JavascriptInterface fun teachWake() = ui {
            trainer?.cancel()
            trainer = WakeTrainer(this@MainActivity) { e -> emit(e.put("type", "teach")) }.also { it.start() }
        }
        @JavascriptInterface fun cancelTeach() = ui { trainer?.cancel(); trainer = null }

        @JavascriptInterface fun setWake(on: Boolean): String {
            if (!on) { Prefs.put("wakeEnabled", false); main.post { WakeWordService.stop(this@MainActivity); main.postDelayed({ pushPerms() }, 300) }; return "" }
            if (!granted(Manifest.permission.RECORD_AUDIO)) { main.post { this@MainActivity.requestPerm("mic") }; return "Önce mikrofon izni ver." }
            if (!VoskModel.isReady(this@MainActivity)) return "Önce ses modelini indir."
            if (!Prefs.isValidWakePhrase(Prefs.normalizePhrase(Prefs.str("wakePhrase")))) return "Uyandırma cümlesinde \"Koser\" olmalı."
            Prefs.put("wakeEnabled", true)
            main.post {
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) this@MainActivity.requestPerm("notif")
                WakeWordService.tryStart(this@MainActivity)
                main.postDelayed({ pushPerms() }, 800)
            }
            return ""
        }

        @JavascriptInterface fun tvTest(op: String) = ui {
            scope.launch {
                val msg = try { TvController.run(this@MainActivity, op, null) } catch (e: Exception) { e.message ?: "Hata" }
                emit(JSONObject().put("type", "tvResult").put("text", msg))
            }
        }
        @JavascriptInterface fun tvReset() = ui { Prefs.clearPins(); Prefs.put("tvToken", ""); Prefs.put("lgClientKey", "") }
        @JavascriptInterface fun testVoice() = ui { voice.speak("Merhaba ${Prefs.str("userName").ifBlank { "efendim" }}. Ben Koser. Sistemler hazır.") { goIdle() } }
        @JavascriptInterface fun openUrl(u: String) = ui { if (u.startsWith("https://")) safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        @JavascriptInterface fun setPanelOpen(b: Boolean) { panelOpen = b }
        @JavascriptInterface fun appInfo(): String = JSONObject().put("version", BuildConfig.VERSION_NAME).put("maker", "Sait").toString()
        @JavascriptInterface fun vibrate() = ui {
            try { getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(18, 80)) } catch (_: Exception) {}
        }
    }

    private var greeted = false

    companion object {
        const val EXTRA_LISTEN = "listen"
        @Volatile var isForeground = false
        @Volatile private var instance: MainActivity? = null
        fun wakeFromService() { instance?.let { a -> a.main.post { a.startListening() } } }
    }
}
