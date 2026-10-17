package com.sait.koserai

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Outline
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
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.webkit.WebViewAssetLoader
import com.sait.koserai.assist.KoserSession
import com.sait.koserai.assist.KoserVoiceService
import com.sait.koserai.assist.Speech
import com.sait.koserai.core.Brain
import com.sait.koserai.core.Prefs
import com.sait.koserai.core.Reply
import com.sait.koserai.core.Voice
import com.sait.koserai.core.Weather
import com.sait.koserai.data.CalendarData
import com.sait.koserai.data.Persona
import com.sait.koserai.data.Reminders
import com.sait.koserai.data.Store
import com.sait.koserai.live.Live
import com.sait.koserai.live.Shortcuts
import com.sait.koserai.tv.TvController
import com.sait.koserai.wake.VoskModel
import com.sait.koserai.wake.WakeTrainer
import com.sait.koserai.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var camView: PreviewView
    private lateinit var voice: Voice
    private lateinit var brain: Brain
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var classicState = "idle"
    private var jsReady = false
    private var listenOnReady = false
    private var greetOnReady = false
    private var panelOpen = false
    private var trainer: WakeTrainer? = null
    private var pendingPerm: String? = null
    private var visionWanted = false
    private var cameraProvider: ProcessCameraProvider? = null
    private val camExec = Executors.newSingleThreadExecutor()
    private var lastFrameAt = 0L
    private var weatherCache: JSONObject? = null
    private var weatherAt = 0L
    private val storeListener: () -> Unit = { main.removeCallbacks(pushDataR); main.postDelayed(pushDataR, 250) }
    private val pushDataR = Runnable { pushData() }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val name = pendingPerm; pendingPerm = null
        val denied = res.filterValues { !it }.keys
        if (denied.isNotEmpty() && denied.none { shouldShowRequestPermissionRationale(it) }) {
            emit(JSONObject().put("type", "toast").put("text", "İzni uygulama ayarlarından açman gerekiyor."))
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        pushPerms()
        if (name == "mic" && res[Manifest.permission.RECORD_AUDIO] == true && listenOnReady) talk(greetOnReady)
        if (name == "camera" && res[Manifest.permission.CAMERA] == true && visionWanted) startCamera()
        if (name == "calendar") pushData()
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val jpeg = withContext(Dispatchers.IO) { loadJpeg(uri) }
            if (jpeg == null) { emit(JSONObject().put("type", "toast").put("text", "Fotoğraf açılamadı.")); return@launch }
            if (!Live.hasKey()) { emit(JSONObject().put("type", "live_error").put("code", "nokey").put("text", "Fotoğraf analizi için Gemini anahtarı gerekli.")); return@launch }
            if (!Live.running) Live.start(this@MainActivity)
            Live.sendImage(jpeg, "Sana bir fotoğraf gönderdim. İçinde ne olduğunu kısaca anlat; yazı varsa oku.")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        instance = this

        voice = Voice(this) { setClassicState("speaking") }
        brain = Brain(this) { r -> deliver(r) }

        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            setBackgroundColor(0xFF02060B.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == "appassets.androidplatform.net") return false
                    safeStart(Intent(Intent.ACTION_VIEW, request.url)); return true
                }
            }
            addJavascriptInterface(Bridge(), "KoserNative")
            loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        }
        camView = PreviewView(this).apply {
            visibility = View.GONE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, 36f) }
            }
            clipToOutline = true
            elevation = 8f
        }
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.62).toInt()
        root.addView(camView, FrameLayout.LayoutParams(w, (w * 4) / 3, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (dm.heightPixels * 0.12).toInt()
        })
        setContentView(root)

        Live.ui = { e -> onLive(e) }
        Store.onChange(storeListener)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (panelOpen) emit(JSONObject().put("type", "back")) else moveTaskToBack(true)
            }
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        val listen = i?.getBooleanExtra(EXTRA_LISTEN, false) == true || i?.action == Intent.ACTION_ASSIST || i?.action == Intent.ACTION_VOICE_COMMAND
        i?.removeExtra(EXTRA_LISTEN)
        if (listen) {
            val greet = Prefs.bool("greetOnWake")
            if (jsReady) main.postDelayed({ if (!Live.running) talk(greet) }, 250) else { listenOnReady = true; greetOnReady = greet }
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        KoserSession.close()
        if (jsReady) { pushPerms(); pushData() }
        if (visionWanted) startCamera()
    }

    override fun onPause() {
        isForeground = false
        stopCamera(keepWanted = true)
        super.onPause()
    }

    override fun onDestroy() {
        instance = null
        if (Live.ui != null) Live.ui = null
        Store.removeListener(storeListener)
        recognizer?.destroy()
        voice.shutdown()
        trainer?.cancel()
        scope.cancel()
        stopCamera(keepWanted = false)
        if (!Live.running) WakeWordService.resume()
        super.onDestroy()
    }

    // ================================================================ JS'ye olaylar

    private fun emit(o: JSONObject) {
        val js = "window.Koser && window.Koser.onNative(" + JSONObject.quote(o.toString()) + ")"
        main.post { if (::web.isInitialized) web.evaluateJavascript(js, null) }
    }

    private fun onLive(e: JSONObject) {
        when (e.optString("type")) {
            "vision" -> if (e.optBoolean("on")) { visionWanted = true; startCamera() } else stopCamera(keepWanted = false)
            "live_error" -> if (e.optString("code") == "mic") requestPerm("mic")
        }
        emit(e)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun permStatus(): JSONObject {
        val pm = getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("mic", granted(Manifest.permission.RECORD_AUDIO))
            .put("contacts", granted(Manifest.permission.READ_CONTACTS))
            .put("phone", granted(Manifest.permission.CALL_PHONE))
            .put("calendar", granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR))
            .put("camera", granted(Manifest.permission.CAMERA))
            .put("notif", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
            .put("overlay", Settings.canDrawOverlays(this))
            .put("assistant", KoserVoiceService.isDefaultAssistant(this))
            .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
            .put("model", VoskModel.isReady(this))
            .put("modelDownloading", VoskModel.downloading)
            .put("wakeRunning", WakeWordService.running)
            .put("recognizer", Speech.bestRecognizer(this) != null)
            .put("tts", voice.hasTurkish)
            .put("key", Live.hasKey())
            .put("liveModel", Prefs.str("liveModel"))
    }

    private fun pushPerms() = emit(JSONObject().put("type", "perms").put("data", permStatus()))
    private fun pushSettings() = emit(JSONObject().put("type", "settings").put("data", settingsJson()))

    private fun settingsJson(): JSONObject = JSONObject(Prefs.all().toString()).apply {
        // anahtarın tamamını arayüze göndermeyelim
        val k = optString("geminiKey")
        put("geminiKey", if (k.length > 8) k.take(4) + "••••••••" + k.takeLast(4) else "")
        put("personaList", Persona.allJson())
        put("voices", Persona.voicesJson())
    }

    private fun pushData() {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                val s = Store.snapshot()
                val o = JSONObject()
                val tasks = s.optJSONArray("tasks") ?: JSONArray()
                val startToday = Store.startOfDay(0)
                o.put("tasks", JSONArray((0 until tasks.length()).map { tasks.getJSONObject(it) }
                    .filter { !it.optBoolean("done") || it.optLong("doneAt") >= startToday }))
                o.put("reminders", JSONArray(Store.pendingReminders()))
                o.put("memories", s.optJSONArray("memories") ?: JSONArray())
                o.put("log", JSONArray(Store.logToday().takeLast(30).reversed()))
                o.put("calendarPerm", CalendarData.canRead(this@MainActivity))
                o.put("today", CalendarData.events(this@MainActivity, 0, 1))
                o.put("tomorrow", CalendarData.events(this@MainActivity, 1, 1))
                o
            }
            emit(JSONObject().put("type", "data").put("data", snap))
        }
    }

    private fun pushWeather(force: Boolean) {
        weatherCache?.let { if (!force && System.currentTimeMillis() - weatherAt < 15 * 60_000) { emit(JSONObject().put("type", "weather").put("data", it)); return } }
        scope.launch {
            val city = Prefs.str("city").ifBlank { "İstanbul" }
            val w = try { withContext(Dispatchers.IO) { Weather.current(city) } } catch (e: Exception) { JSONObject().put("error", "Hava durumuna ulaşılamadı").put("city", city) }
            if (!w.has("error")) { weatherCache = w; weatherAt = System.currentTimeMillis() }
            emit(JSONObject().put("type", "weather").put("data", w))
        }
    }

    private fun requestPerm(name: String) {
        val perms = when (name) {
            "mic" -> arrayOf(Manifest.permission.RECORD_AUDIO)
            "contacts" -> arrayOf(Manifest.permission.READ_CONTACTS)
            "phone" -> arrayOf(Manifest.permission.CALL_PHONE)
            "calendar" -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            "camera" -> arrayOf(Manifest.permission.CAMERA)
            "notif" -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else return
            "overlay" -> { safeStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
            "battery" -> { safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); return }
            "assistant" -> { if (!safeStart(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))) safeStart(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); return }
            else -> return
        }
        pendingPerm = name
        permLauncher.launch(perms)
    }

    private fun safeStart(i: Intent): Boolean = try { startActivity(i); true } catch (e: Exception) { false }

    // ================================================================ konuşma

    /** Mikrofon düğmesi: anahtar varsa gerçek zamanlı (Gemini Live), yoksa basit mod. */
    fun talk(greet: Boolean = false) {
        if (!granted(Manifest.permission.RECORD_AUDIO)) { listenOnReady = true; greetOnReady = greet; requestPerm("mic"); return }
        listenOnReady = false
        if (Live.hasKey()) {
            voice.stop(); recognizer?.cancel()
            if (Live.running) Live.stop() else Live.start(this, greet = greet)
        } else {
            if (classicState == "listening") stopClassic() else startClassic()
        }
    }

    private fun sendText(t: String) {
        if (t.isBlank()) return
        if (Live.hasKey()) {
            if (Live.running) Live.sendText(t) else Live.start(this, text = t)
        } else {
            voice.stop(); recognizer?.cancel()
            processClassic(t)
        }
    }

    // ---------------- basit mod (anahtar yokken): telefonun ses tanıması + yerel komutlar + telefon sesi

    private fun setClassicState(s: String) { classicState = s; emit(JSONObject().put("type", "state").put("value", s)) }

    private fun startClassic() {
        voice.stop()
        WakeWordService.pause()
        recognizer?.destroy()
        val comp = Speech.bestRecognizer(this)
        val r = if (comp != null) SpeechRecognizer.createSpeechRecognizer(this, comp) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setClassicState("listening") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { emit(JSONObject().put("type", "level").put("v", ((rmsdB + 2) / 12f).coerceIn(0f, 1f).toDouble())) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setClassicState("thinking") }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) { main.postDelayed({ startClassic() }, 400); return }
                emit(JSONObject().put("type", "hint").put("text", if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "Bir şey duyamadım." else "Ses tanıma hatası ($error)."))
                goIdle()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) goIdle() else processClassic(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    emit(JSONObject().put("type", "user").put("text", it).put("final", false))
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        setClassicState("listening")
        try { r.startListening(i) } catch (e: Exception) { deliver(Reply("Ses tanıma başlatılamadı.")) }
    }

    private fun processClassic(text: String) {
        emit(JSONObject().put("type", "user").put("text", text).put("final", true))
        setClassicState("thinking")
        scope.launch {
            val reply = try { brain.handle(text) } catch (e: Exception) { Reply("Bir hata oluştu.") }
            deliver(reply)
        }
    }

    private fun deliver(r: Reply) {
        r.ui?.let { ui ->
            when (ui.optString("type")) {
                "perm" -> main.postDelayed({ requestPerm(ui.optString("name")) }, 1500)
                "stop" -> { stopClassic(); return }
                "theme" -> { Prefs.put("persona", when (ui.optString("id")) { "kizil" -> "ultron"; "altin" -> "nova"; else -> "koser" }); pushSettings() }
                else -> emit(ui)
            }
        }
        if (r.say.isBlank()) { goIdle(); return }
        emit(JSONObject().put("type", "model").put("text", r.say).put("final", true))
        WakeWordService.pause()
        voice.speak(r.say) { if (r.listenAgain) startClassic() else goIdle() }
    }

    private fun goIdle() { setClassicState("idle"); if (!Live.running) WakeWordService.resume() }

    private fun stopClassic() { recognizer?.cancel(); voice.stop(); brain.cancelPending(); goIdle() }

    // ================================================================ kamera (görme)

    private fun startCamera() {
        if (!granted(Manifest.permission.CAMERA)) { requestPerm("camera"); return }
        if (!isForeground) return
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val provider = f.get(); cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(camView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(camExec) { img ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastFrameAt >= 1000 && Live.visionOn) {
                            lastFrameAt = now
                            var bmp = img.toBitmap()
                            val rot = img.imageInfo.rotationDegrees
                            val scale = 768f / maxOf(bmp.width, bmp.height)
                            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); if (rot != 0) postRotate(rot.toFloat()) }
                            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                            val out = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 65, out)
                            Live.sendVideoFrame(out.toByteArray())
                        }
                    } catch (_: Exception) {
                    } finally { img.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camView.visibility = View.VISIBLE
            } catch (e: Exception) {
                emit(JSONObject().put("type", "toast").put("text", "Kamera açılamadı."))
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera(keepWanted: Boolean) {
        if (!keepWanted) visionWanted = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        if (::camView.isInitialized) camView.visibility = View.GONE
    }

    private fun loadJpeg(uri: Uri): ByteArray? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        bmp?.let {
            val s = 1024f / maxOf(it.width, it.height)
            val b = if (s < 1f) Bitmap.createScaledBitmap(it, (it.width * s).toInt(), (it.height * s).toInt(), true) else it
            ByteArrayOutputStream().also { o -> b.compress(Bitmap.CompressFormat.JPEG, 80, o) }.toByteArray()
        }
    } catch (e: Exception) { null }

    // ================================================================ köprü (web arayüzü -> Kotlin)

    inner class Bridge {
        private fun ui(block: () -> Unit) { main.post(block) }

        @JavascriptInterface fun ready() = ui {
            jsReady = true
            pushSettings(); pushPerms(); pushData(); pushWeather(false)
            emit(JSONObject().put("type", "state").put("value", if (Live.running) Live.state else "idle"))
            if (listenOnReady) main.postDelayed({ talk(greetOnReady) }, 400)
            if (Prefs.bool("wakeEnabled") && !WakeWordService.running) WakeWordService.tryStart(this@MainActivity)
        }

        @JavascriptInterface fun talk() = ui { this@MainActivity.talk(false) }
        @JavascriptInterface fun stopTalk() = ui { if (Live.running) Live.stop(); stopClassic() }
        @JavascriptInterface fun sendText(t: String) = ui { this@MainActivity.sendText(t) }
        @JavascriptInterface fun setMute(b: Boolean) { Live.muted = b }
        @JavascriptInterface fun vision(on: Boolean) = ui {
            if (!Live.hasKey()) { emit(JSONObject().put("type", "live_error").put("code", "nokey").put("text", "Kamera ile görme için Gemini anahtarı gerekli.")); return@ui }
            if (on) { if (!Live.running) Live.start(this@MainActivity, text = "Kamerayı açtım. Gördüğünü kısaca söyle ve ne yapmamı istediğimi sor.") ; Live.setVision(true) }
            else Live.setVision(false)
        }
        @JavascriptInterface fun pickPhoto() = ui { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

        @JavascriptInterface fun getSettings(): String = settingsJson().toString()
        @JavascriptInterface fun saveSettings(json: String): String {
            val o = try { JSONObject(json) } catch (e: Exception) { return "Ayar kaydedilemedi." }
            o.remove("geminiKey") // anahtar yalnızca testKey ile kaydedilir
            val err = Prefs.merge(o)
            if (err == null) {
                if (o.has("tvIp") || o.has("tvType")) { Prefs.clearPins(); Prefs.put("tvToken", ""); Prefs.put("lgClientKey", "") }
                if (o.has("city")) main.post { pushWeather(true) }
            }
            return err ?: ""
        }
        @JavascriptInterface fun permStatus(): String = this@MainActivity.permStatus().toString()
        @JavascriptInterface fun requestPerm(name: String) = ui { this@MainActivity.requestPerm(name) }

        // ---- Gemini anahtarı
        @JavascriptInterface fun pasteClipboard(): String {
            val cm = getSystemService(ClipboardManager::class.java)
            return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.trim() ?: ""
        }
        @JavascriptInterface fun testKey(key: String) = ui {
            emit(JSONObject().put("type", "keyResult").put("busy", true))
            scope.launch {
                val r = Live.testKey(key)
                emit(JSONObject().put("type", "keyResult").put("ok", r.ok).put("message", r.message).put("model", r.model ?: ""))
                pushSettings(); pushPerms()
            }
        }
        @JavascriptInterface fun removeKey() = ui { if (Live.running) Live.stop(); Prefs.put("geminiKey", ""); Prefs.put("liveModel", ""); pushSettings(); pushPerms() }

        // ---- karakterler
        @JavascriptInterface fun setPersona(id: String) = ui {
            Prefs.put("persona", id); pushSettings()
            if (Live.running) { Live.stop(); main.postDelayed({ Live.start(this@MainActivity, greet = true) }, 500) }
        }
        @JavascriptInterface fun savePersona(id: String, name: String, voiceName: String) = ui {
            val all = Prefs.all().optJSONObject("personas") ?: JSONObject()
            all.put(id, JSONObject().put("name", name.trim().take(16)).put("voice", voiceName))
            Prefs.put("personas", all); pushSettings()
        }

        // ---- veriler
        @JavascriptInterface fun refresh() = ui { pushData(); pushWeather(false); pushPerms() }
        @JavascriptInterface fun addTask(title: String) { if (title.isNotBlank()) { Store.addTask(title, null); Store.log("task", "Görev eklendi: $title") } }
        @JavascriptInterface fun toggleTask(id: String) = Store.toggleTask(id)
        @JavascriptInterface fun deleteTask(id: String) = Store.deleteTask(id)
        @JavascriptInterface fun deleteReminder(id: String) { Reminders.cancel(this@MainActivity, id); Store.deleteReminder(id) }
        @JavascriptInterface fun deleteMemory(id: String) = Store.deleteMemory(id)
        @JavascriptInterface fun clearHistory() = Store.clearHistory()
        @JavascriptInterface fun runShortcut(name: String) = ui {
            scope.launch {
                val r = withContext(Dispatchers.IO) { Shortcuts.run(name) }
                emit(JSONObject().put("type", "toast").put("text", r.optString("result").ifBlank { r.optString("error") }))
            }
        }
        @JavascriptInterface fun testShortcut(action: String, header: String) = ui {
            scope.launch {
                val msg = try { withContext(Dispatchers.IO) { Shortcuts.exec(action, header) }; "Çalıştı ✓" } catch (e: Exception) { "Hata: ${e.message}" }
                emit(JSONObject().put("type", "toast").put("text", msg))
            }
        }

        // ---- TV
        @JavascriptInterface fun tvTest(op: String) = ui {
            scope.launch {
                val msg = try { TvController.run(this@MainActivity, op, null) } catch (e: Exception) { e.message ?: "Hata" }
                emit(JSONObject().put("type", "tvResult").put("text", msg))
            }
        }
        @JavascriptInterface fun tvReset() = ui { Prefs.clearPins(); Prefs.put("tvToken", ""); Prefs.put("lgClientKey", "") }

        // ---- Hey Koser
        @JavascriptInterface fun downloadModel() = ui {
            VoskModel.download(this@MainActivity,
                progress = { p -> emit(JSONObject().put("type", "model").put("progress", p)) },
                done = { err -> emit(JSONObject().put("type", "model").put("done", true).put("error", err ?: "")); main.post { pushPerms() } })
            pushPerms()
        }
        @JavascriptInterface fun deleteModel() = ui { Prefs.put("wakeEnabled", false); WakeWordService.stop(this@MainActivity); VoskModel.delete(this@MainActivity); pushPerms() }
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

        @JavascriptInterface fun openUrl(u: String) = ui { if (u.startsWith("https://")) safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        @JavascriptInterface fun setPanelOpen(b: Boolean) { panelOpen = b }
        @JavascriptInterface fun appInfo(): String = JSONObject().put("version", BuildConfig.VERSION_NAME).put("maker", "Sait").toString()
        @JavascriptInterface fun vibrate() = ui {
            try { getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createOneShot(16, 70)) } catch (_: Exception) {}
        }
    }

    companion object {
        const val EXTRA_LISTEN = "listen"
        @Volatile var isForeground = false
        @Volatile private var instance: MainActivity? = null
        fun wakeFromService() { instance?.let { a -> a.main.post { if (!Live.running) a.talk(Prefs.bool("greetOnWake")) } } }
    }
}
