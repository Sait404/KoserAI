package com.sait.koserai.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sait.koserai.KoserApp
import com.sait.koserai.MainActivity
import com.sait.koserai.R
import com.sait.koserai.core.Net
import com.sait.koserai.core.Prefs
import com.sait.koserai.data.Persona
import com.sait.koserai.data.Store
import com.sait.koserai.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Gemini Live ile gerçek zamanlı sesli konuşma.
 * Mikrofon (16 kHz PCM) -> WebSocket -> Gemini -> ses (24 kHz PCM) + yazıya döküm + araç çağrıları.
 */
object Live {
    private const val HOST = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    val FALLBACK_MODELS = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025")

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()

    @Volatile var ui: ((JSONObject) -> Unit)? = null
    @Volatile var running = false; private set
    @Volatile private var ws: WebSocket? = null
    @Volatile private var setupDone = false
    @Volatile private var userStopped = false
    @Volatile private var switching = false
    private var ctx: Context? = null
    private var models = listOf<String>()
    private var modelIdx = 0
    private var level = 0
    private var resumeHandle: String? = null
    private var reconnects = 0
    private var mic: Mic? = null
    private var player: Player? = null
    private val userBuf = StringBuilder()
    private val modelBuf = StringBuilder()
    private var userFinal = true
    @Volatile private var lastUserTextAt = 0L
    @Volatile private var lastVoiceAt = 0L
    @Volatile private var lastModelAt = 0L
    @Volatile private var lastActivityAt = 0L
    private val toolsRunning = AtomicInteger(0)
    @Volatile private var restartRequested = false
    @Volatile private var endRequested = false
    @Volatile var visionOn = false; private set
    @Volatile var muted = false
    private val pending = ArrayList<JSONObject>()
    private var greetOnStart = false
    private var lastState = ""
    private var lastLevel = -1.0

    // ================================================================ dışarıya açık

    fun hasKey() = Prefs.str("geminiKey").isNotBlank()

    /** MainActivity'den (ön plandayken) çağrılır. */
    fun start(c: Context, greet: Boolean = false, text: String? = null) {
        if (running) { text?.let { sendText(it) }; return }
        if (!hasKey()) { emit(JSONObject().put("type", "live_error").put("code", "nokey").put("text", "Gerçek zamanlı konuşma için Gemini anahtarı gerekli.")); return }
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            emit(JSONObject().put("type", "live_error").put("code", "mic").put("text", "Mikrofon izni gerekli.")); return
        }
        running = true
        greetOnStart = greet
        synchronized(pending) { pending.clear(); text?.let { pending.add(textMsg(it)); emitUser(it) } }
        setState("connecting")
        try { c.startForegroundService(Intent(c, LiveService::class.java)) } catch (e: Exception) {
            running = false; setState("idle")
            emit(JSONObject().put("type", "live_error").put("code", "service").put("text", "Konuşma başlatılamadı: ${e.message}"))
        }
    }

    fun stop() {
        if (!running) return
        userStopped = true
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        cleanup()
    }

    fun sendText(t: String) {
        if (t.isBlank()) return
        emitUser(t)
        Store.addHistory("user", t)
        lastActivityAt = System.currentTimeMillis()
        val m = textMsg(t)
        if (setupDone) ws?.send(m.toString()) else synchronized(pending) { pending.add(m) }
    }

    fun sendImage(jpeg: ByteArray, prompt: String) {
        val v = videoMsg(jpeg)
        if (setupDone) { ws?.send(v.toString()); sendText(prompt) }
        else {
            synchronized(pending) { pending.add(v); pending.add(textMsg(prompt)) }
            emitUser(prompt)
        }
    }

    fun sendVideoFrame(jpeg: ByteArray) {
        if (setupDone && visionOn) ws?.send(videoMsg(jpeg).toString())
    }

    fun setVision(on: Boolean) {
        visionOn = on
        emit(JSONObject().put("type", "vision").put("on", on))
    }

    fun requestRestart() { restartRequested = true }
    fun requestEnd() { endRequested = true }

    // ================================================================ servis tarafı

    internal fun begin(service: Context) {
        ctx = service
        userStopped = false; switching = false; restartRequested = false; endRequested = false
        resumeHandle = null; reconnects = 0; level = Prefs.int("liveLevel").coerceIn(0, 2); modelIdx = 0
        models = (listOf(Prefs.str("liveModel")).filter { it.isNotBlank() } + FALLBACK_MODELS).distinct()
        WakeWordService.pause()
        AudioRoute.enter(service)
        player = Player()
        lastActivityAt = System.currentTimeMillis()
        connect()
        main.post(ticker)
    }

    private fun connect() {
        setupDone = false
        val key = Prefs.str("geminiKey").trim()
        val req = Request.Builder().url("$HOST?key=$key").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupJson(models[modelIdx], level).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket === ws) handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (webSocket === ws) handle(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (webSocket === ws) closed(code, reason, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === ws) closed(response?.code ?: -1, response?.message ?: "", t)
            }
        })
    }

    private fun closed(code: Int, reason: String, t: Throwable?) {
        if (userStopped || !running) return
        if (switching) { switching = false; main.post { connect() }; return }
        if (!setupDone) {
            when (classify(code, reason, t)) {
                "key" -> return fail("key", "Gemini anahtarı geçersiz ya da yetkisiz. Ayarlar'dan anahtarı yeniden test et." + detail(reason))
                "model" -> {
                    if (modelIdx < models.size - 1) { modelIdx++; level = 0; main.postDelayed({ connect() }, 300); return }
                    return fail("model", "Bu anahtarla canlı ses modeli açılamadı." + detail(reason))
                }
                "invalid" -> {
                    if (level < 2) { level++; main.postDelayed({ connect() }, 300); return }
                    if (modelIdx < models.size - 1) { modelIdx++; level = 0; main.postDelayed({ connect() }, 300); return }
                    return fail("setup", "Gemini bağlantı ayarlarını kabul etmedi." + detail(reason))
                }
                "quota" -> return fail("quota", "Gemini kullanım sınırına takıldı. Bir dakika bekleyip tekrar dene." + detail(reason))
                "net" -> {
                    if (reconnects < 2) { reconnects++; main.postDelayed({ connect() }, 1200); return }
                    return fail("net", "Gemini'ye bağlanamadım. İnternet bağlantını kontrol et." + detail(t?.message ?: reason))
                }
                else -> return fail("setup", "Gemini bağlantıyı kapattı." + detail(reason.ifBlank { "kod $code" }))
            }
        }
        // konuşma sırasında koptu: kaldığı yerden devam etmeyi dene
        if (reconnects < 4) {
            reconnects++
            setState("connecting")
            main.postDelayed({ if (running && !userStopped) connect() }, 600L * reconnects)
        } else fail("lost", "Bağlantı koptu.")
    }

    private fun detail(r: String) = if (r.isBlank()) "" else " (Google: ${r.take(180)})"

    /** Google'ın kapanış nedenini sınıflandır */
    internal fun classify(code: Int, reason: String, t: Throwable?): String {
        val r = reason.lowercase()
        return when {
            r.contains("api key not valid") || r.contains("api_key_invalid") || r.contains("unauthenticated") || r.contains("invalid api key") ||
                (r.contains("api key") && !r.contains("model")) -> "key"
            r.contains("not found") || r.contains("not supported") || r.contains("unsupported") || r.contains("is not available") || r.contains("does not exist") -> "model"
            r.contains("quota") || r.contains("resource_exhausted") || r.contains("resource exhausted") || r.contains("rate limit") || r.contains("too many requests") -> "quota"
            code == 1007 || r.contains("invalid") || r.contains("unknown name") || r.contains("cannot find field") -> "invalid"
            t != null && reason.isBlank() -> "net"
            else -> "other"
        }
    }

    private fun fail(code: String, msg: String) {
        emit(JSONObject().put("type", "live_error").put("code", code).put("text", msg))
        main.post { cleanup() }
    }

    private fun cleanup() {
        if (!running && ctx == null) return
        running = false; setupDone = false
        val w = ws; ws = null
        try { w?.cancel() } catch (_: Exception) {}
        mic?.stop(); mic = null
        player?.release(); player = null
        if (visionOn) setVision(false)
        ctx?.let { c -> AudioRoute.exit(c); c.stopService(Intent(c, LiveService::class.java)) }
        ctx = null
        finalizeTurn()
        main.removeCallbacks(ticker)
        setState("idle")
        WakeWordService.resume()
    }

    // ================================================================ protokol

    private fun persona() = Persona.active()

    private fun setupJson(model: String, lvl: Int): JSONObject {
        val p = persona()
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", p.voice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt(p)))))
        val tools = JSONArray()
        if (lvl < 2) tools.put(JSONObject().put("googleSearch", JSONObject()))
        tools.put(JSONObject().put("functionDeclarations", Tools.declarations()))
        setup.put("tools", tools)
        if (lvl < 2) {
            setup.put("inputAudioTranscription", JSONObject())
            setup.put("outputAudioTranscription", JSONObject())
        }
        if (lvl == 0) {
            setup.put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject().put("silenceDurationMs", 700)))
            setup.put("sessionResumption", resumeHandle?.let { JSONObject().put("handle", it) } ?: JSONObject())
            setup.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        }
        return JSONObject().put("setup", setup)
    }

    private fun systemPrompt(p: Persona): String {
        val user = Prefs.str("userName").ifBlank { "efendim" }
        val now = SimpleDateFormat("d MMMM yyyy EEEE, HH:mm", Locale("tr")).format(Date())
        val mem = Store.memories()
        val hist = Store.recentHistory(12).joinToString("\n") { (if (it.optString("role") == "user") "Kullanıcı: " else "Sen: ") + it.optString("text") }
        val others = Persona.all().joinToString(", ") { it.name }
        return buildString {
            appendLine("Sen ${p.name}'sın: KoserAI uygulamasında yaşayan, telefonda gerçek işler yapabilen kişisel yapay zekâ asistanı. KoserAI'yi Sait geliştirdi; sorulursa \"Beni Sait geliştirdi\" de.")
            appendLine("KİŞİLİĞİN: ${p.style}")
            appendLine("Uygulamadaki diğer karakterler: $others. Kullanıcı isterse switch_persona ile geçersin.")
            appendLine()
            appendLine("KULLANICI: Ona \"$user\" diye hitap et (aşırı tekrar etme). Şehri: ${Prefs.str("city").ifBlank { "bilinmiyor" }}. Şu an: $now (telefonun yerel saati).")
            appendLine()
            appendLine("KONUŞMA KURALLARI:")
            appendLine("- Varsayılan dilin Türkçe. Kullanıcı başka dilde konuşursa o dile geç.")
            appendLine("- Cevapların sesli okunuyor: kısa, doğal ve akıcı konuş. Liste, madde işareti, emoji, markdown kullanma. Genelde 1-3 cümle yeter.")
            appendLine("- Güncel bilgi gereken her şeyde (haber, maç sonucu, döviz, altın, borsa, olaylar) Google araması yap; uydurma.")
            appendLine("- Telefonda bir iş istendiğinde ilgili aracı hemen kullan; basit işlerde ayrıca onay isteme. İşi yaptıktan sonra ne yaptığını kısaca söyle.")
            appendLine("- Mesaj (WhatsApp/SMS) göndermeden önce MUTLAKA içeriği ve alıcıyı okuyup onay al (araç bunu ister).")
            appendLine("- Tarih/saat isteyen araçlarda 'yarın 10' gibi ifadeleri şu anki tarihe göre YYYY-MM-DDTHH:MM biçimine çevir.")
            appendLine("- Bir araç hata döndürürse bunu açıkça ve nasıl düzeleceğiyle birlikte söyle; başarmış gibi davranma.")
            appendLine("- Kullanıcı kendisi hakkında kalıcı, önemli bir bilgi verdiğinde (adı, sevdikleri, ailesi, önemli tarihler, tercihleri) remember aracıyla kaydet.")
            appendLine("- 'Bugün neler var', 'bugün ne yaptın', 'sırada ne var' gibi sorularda get_daily_brief kullan.")
            appendLine("- Kamera açıkken gelen görüntüler kullanıcının telefonunun kamerasıdır; sorulursa gördüğünü anlat, yazıları oku.")
            appendLine("- Kullanıcı vedalaşınca kısa bir veda et ve end_conversation çağır.")
            if (mem.isNotEmpty()) { appendLine(); appendLine("KULLANICI HAKKINDA BİLDİKLERİN:"); mem.takeLast(40).forEach { appendLine("- $it") } }
            if (hist.isNotBlank()) { appendLine(); appendLine("SON KONUŞMALARIN (bağlam için):"); appendLine(hist) }
        }
    }

    private fun textMsg(t: String) = JSONObject().put("realtimeInput", JSONObject().put("text", t))
    private fun videoMsg(jpeg: ByteArray) = JSONObject().put("realtimeInput", JSONObject().put("video",
        JSONObject().put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("mimeType", "image/jpeg")))
    private fun audioMsg(b64: String) = "{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}"

    private fun handle(raw: String) {
        val m = try { JSONObject(raw) } catch (e: Exception) { return }
        when {
            m.has("setupComplete") -> onSetup()
            m.has("serverContent") -> onContent(m.getJSONObject("serverContent"))
            m.has("toolCall") -> onToolCall(m.getJSONObject("toolCall"))
            m.has("goAway") -> { if (resumeHandle != null) { switching = true; try { ws?.close(1000, "resume") } catch (_: Exception) {} } }
            m.has("sessionResumptionUpdate") -> {
                val u = m.getJSONObject("sessionResumptionUpdate")
                if (u.optBoolean("resumable") && u.optString("newHandle").isNotBlank()) resumeHandle = u.optString("newHandle")
            }
        }
    }

    private fun onSetup() {
        setupDone = true
        reconnects = 0
        Prefs.put("liveModel", models[modelIdx])
        Prefs.put("liveLevel", level)
        main.post {
            val c = ctx ?: return@post
            if (mic == null) {
                mic = Mic(c) { b64, rms ->
                    if (rms > 0.06) lastVoiceAt = System.currentTimeMillis()
                    micLevel = rms
                    val p = player
                    val block = muted || (!Prefs.bool("bargeIn") && p != null && p.isPlaying())
                    if (setupDone && !block) ws?.send(audioMsg(b64))
                }.also { if (!it.start()) fail("mic", "Mikrofon açılamadı. Başka bir uygulama kullanıyor olabilir.") }
            }
            setState("listening")
            val list = synchronized(pending) { pending.toList().also { pending.clear() } }
            list.forEach { ws?.send(it.toString()) }
            if (greetOnStart && list.isEmpty()) {
                greetOnStart = false
                ws?.send(textMsg("[Sistem notu: Kullanıcı seni sesle çağırdı. Kişiliğine uygun, tek kısa cümleyle karşıla ve dinlediğini belli et.]").toString())
            }
        }
    }

    @Volatile private var micLevel = 0.0

    private fun onContent(sc: JSONObject) {
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            if (userFinal) { userBuf.setLength(0); userFinal = false }
            userBuf.append(it); lastUserTextAt = System.currentTimeMillis(); lastActivityAt = lastUserTextAt
            emit(JSONObject().put("type", "user").put("text", userBuf.toString().trim()).put("final", false))
        }
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
            val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
            val mime = inline.optString("mimeType")
            if (!mime.startsWith("audio")) continue
            val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
            finalizeUser()
            lastModelAt = System.currentTimeMillis(); lastActivityAt = lastModelAt
            player?.enqueue(Base64.decode(inline.optString("data"), Base64.DEFAULT), rate)
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            finalizeUser()
            modelBuf.append(it)
            emit(JSONObject().put("type", "model").put("text", modelBuf.toString().trim()).put("final", false))
        }
        if (sc.optBoolean("interrupted")) { player?.flush(); finalizeModel(interrupted = true) }
        if (sc.optBoolean("turnComplete")) {
            finalizeTurn()
            if (restartRequested || endRequested) afterSpeech {
                if (endRequested) { endRequested = false; stop() }
                else if (restartRequested) {
                    restartRequested = false; resumeHandle = null; switching = true
                    emit(JSONObject().put("type", "persona").put("data", Persona.active().toJson()))
                    try { ws?.close(1000, "persona") } catch (_: Exception) {}
                }
            }
        }
    }

    private fun finalizeUser() {
        if (!userFinal && userBuf.isNotBlank()) {
            val t = userBuf.toString().trim()
            emit(JSONObject().put("type", "user").put("text", t).put("final", true))
            Store.addHistory("user", t)
        }
        userFinal = true
    }

    private fun finalizeModel(interrupted: Boolean = false) {
        if (modelBuf.isNotBlank()) {
            val t = modelBuf.toString().trim()
            emit(JSONObject().put("type", "model").put("text", t).put("final", true).put("interrupted", interrupted))
            Store.addHistory("model", t)
        }
        modelBuf.setLength(0)
    }

    private fun finalizeTurn() { finalizeUser(); finalizeModel() }

    /** Ses çalması bitince çalıştır */
    private fun afterSpeech(block: () -> Unit) {
        val start = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val p = player
                if (p != null && p.isPlaying() && System.currentTimeMillis() - start < 30_000) main.postDelayed(this, 120) else block()
            }
        }
        main.postDelayed(r, 200)
    }

    private val TOOL_LABELS = mapOf(
        "open_app" to "Uygulama açıyor", "send_whatsapp" to "WhatsApp mesajı", "send_sms" to "SMS", "call_contact" to "Arama yapıyor",
        "set_alarm" to "Alarm kuruyor", "set_timer" to "Zamanlayıcı", "add_calendar_event" to "Takvime ekliyor", "get_calendar_events" to "Takvime bakıyor",
        "add_reminder" to "Hatırlatma kuruyor", "list_reminders" to "Hatırlatmalara bakıyor", "add_task" to "Görev ekliyor", "complete_task" to "Görevi tamamlıyor",
        "list_tasks" to "Görevlere bakıyor", "remember" to "Hafızaya kaydediyor", "forget" to "Hafızadan siliyor", "get_weather" to "Hava durumuna bakıyor",
        "navigate" to "Yol tarifi", "media_control" to "Medya", "youtube_search" to "YouTube", "device_control" to "Telefon ayarı",
        "tv_control" to "Televizyon", "run_shortcut" to "Kestirme çalıştırıyor", "get_daily_brief" to "Günü özetliyor",
        "switch_persona" to "Karakter değiştiriyor", "camera_vision" to "Kamera", "get_device_status" to "Cihaz durumu", "end_conversation" to "Vedalaşıyor",
    )

    private fun onToolCall(tc: JSONObject) {
        val calls = tc.optJSONArray("functionCalls") ?: return
        for (i in 0 until calls.length()) {
            val fc = calls.getJSONObject(i)
            val name = fc.optString("name"); val id = fc.optString("id")
            val args = fc.optJSONObject("args") ?: JSONObject()
            toolsRunning.incrementAndGet()
            emit(JSONObject().put("type", "tool").put("name", name).put("label", TOOL_LABELS[name] ?: name))
            scope.launch {
                val c = ctx
                val res = if (c == null) JSONObject().put("error", "oturum kapandı") else Tools.run(c, name, args)
                val resp = JSONObject().put("toolResponse", JSONObject().put("functionResponses",
                    JSONArray().put(JSONObject().put("id", id).put("name", name).put("response", res))))
                ws?.send(resp.toString())
                toolsRunning.decrementAndGet()
                emit(JSONObject().put("type", "tool_done").put("name", name).put("ok", !res.has("error")))
            }
        }
    }

    // ================================================================ durum / animasyon

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val p = player
            val playing = p?.isPlaying() == true
            val st = when {
                !setupDone -> "connecting"
                playing -> "speaking"
                toolsRunning.get() > 0 -> "thinking"
                !userFinal && now - lastUserTextAt > 450 && now - lastVoiceAt > 450 -> "thinking"
                userFinal && lastUserTextAt > lastModelAt && now - lastUserTextAt < 6000 && modelBuf.isEmpty() -> "thinking"
                else -> "listening"
            }
            setState(st)
            val lvl = if (playing) p!!.level() else if (st == "listening") micLevel else 0.0
            if (Math.abs(lvl - lastLevel) > 0.015) { lastLevel = lvl; emit(JSONObject().put("type", "level").put("v", lvl)) }
            if (playing || now - lastVoiceAt < 1500) lastActivityAt = maxOf(lastActivityAt, now)
            val idleLimit = Prefs.int("autoEndSec").takeIf { it > 0 } ?: 45
            if (setupDone && !visionOn && now - lastActivityAt > idleLimit * 1000L) { stop(); return }
            main.postDelayed(this, 60)
        }
    }

    private fun setState(s: String) {
        if (s == lastState) return
        lastState = s
        emit(JSONObject().put("type", "state").put("value", s))
    }

    val state get() = lastState

    private fun emitUser(t: String) = emit(JSONObject().put("type", "user").put("text", t).put("final", true))

    private fun emit(o: JSONObject) { val u = ui ?: return; main.post { u(o) } }

    // ================================================================ anahtar testi

    data class KeyResult(val ok: Boolean, val message: String, val model: String?, val liveModels: List<String>)

    /** Anahtarı gerçekten dener; anahtarın erişebildiği canlı ses modellerini bulur ve en iyisini seçer. */
    suspend fun testKey(keyRaw: String): KeyResult = withContext(Dispatchers.IO) {
        val key = keyRaw.trim().trim('"', '\'', ' ')
        if (key.length < 20) return@withContext KeyResult(false, "Anahtar çok kısa görünüyor. Tamamını kopyaladığından emin ol.", null, emptyList())
        val live = ArrayList<String>()
        var token: String? = null
        try {
            do {
                val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200" + (token?.let { "&pageToken=$it" } ?: "")
                val r = Net.http.newCall(Request.Builder().url(url).header("x-goog-api-key", key).build()).execute()
                val body = r.body?.string() ?: ""
                r.close()
                if (!r.isSuccessful) {
                    val msg = try { JSONObject(body).getJSONObject("error").optString("message") } catch (e: Exception) { "" }
                    val human = when {
                        r.code == 400 && msg.contains("API key", true) -> "Bu anahtar geçersiz. AI Studio'dan yeniden kopyala."
                        r.code == 403 -> "Anahtar reddedildi (yetki yok). AI Studio'da yeni bir anahtar oluşturmayı dene."
                        r.code == 429 -> "Çok fazla deneme yapıldı, bir dakika sonra tekrar dene."
                        else -> "Anahtar doğrulanamadı (${r.code}). $msg"
                    }
                    return@withContext KeyResult(false, human, null, emptyList())
                }
                Prefs.put("geminiKey", key)
                val j = JSONObject(body)
                val arr = j.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val mo = arr.getJSONObject(i)
                    val methods = mo.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    val bidi = (0 until methods.length()).any { methods.optString(it) == "bidiGenerateContent" }
                    if (bidi) live.add(mo.optString("name").removePrefix("models/"))
                }
                token = j.optString("nextPageToken").takeIf { it.isNotBlank() }
            } while (token != null)
        } catch (e: Exception) {
            return@withContext KeyResult(false, "İnternete bağlanılamadı. Bağlantını kontrol edip tekrar dene.", null, emptyList())
        }
        // listede görünenleri ve bilinen modelleri sırayla GERÇEKTEN dene
        val candidates = (FALLBACK_MODELS.filter { it in live } +
            live.filter { !it.contains("translate") && (it.contains("live") || it.contains("native-audio")) } +
            FALLBACK_MODELS).distinct().take(6)
        var last = ""
        for (m in candidates) {
            for (lvl in 0..2) {
                val (res, why) = probe(key, m, lvl)
                if (res == "ok") {
                    Prefs.put("liveModel", m); Prefs.put("liveLevel", lvl)
                    return@withContext KeyResult(true, "Anahtar çalışıyor ✓ Canlı ses bağlantısı kuruldu ($m).", m, live)
                }
                last = why
                when (res) {
                    "key" -> return@withContext KeyResult(false, "Bu anahtar geçersiz. AI Studio'dan yeniden kopyala." + detail(why), null, live)
                    "quota" -> return@withContext KeyResult(false, "Anahtar geçerli ama Gemini şu an kullanım sınırı veriyor. Bir dakika sonra tekrar dene." + detail(why), null, live)
                    "invalid" -> continue
                    else -> break
                }
            }
        }
        KeyResult(false, "Anahtar geçerli ama canlı ses bağlantısı kurulamadı. Bu mesajın ekran görüntüsünü gönder." + detail(last), null, live)
    }

    /** Tek bir model/ayar ile gerçek bağlantı kurmayı dener. ("ok"|"key"|"model"|"quota"|"invalid"|"net"|"other", neden) */
    private suspend fun probe(key: String, model: String, lvl: Int): Pair<String, String> {
        val done = kotlinx.coroutines.CompletableDeferred<Pair<String, String>>()
        val w = client.newWebSocket(Request.Builder().url("$HOST?key=$key").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupJson(model, lvl).toString()) }
            private fun msg(t: String) { if (t.contains("setupComplete")) done.complete("ok" to "") }
            override fun onMessage(webSocket: WebSocket, text: String) = msg(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = msg(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { done.complete(classify(code, reason, null) to "[$code] $reason"); webSocket.close(1000, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = (response?.let { "HTTP ${it.code} ${it.message}" } ?: "") + " " + (t.message ?: "")
                done.complete(classify(response?.code ?: -1, why, t) to why.trim())
            }
        })
        val r = kotlinx.coroutines.withTimeoutOrNull(12_000) { done.await() } ?: ("net" to "zaman aşımı")
        try { w.close(1000, "test") } catch (_: Exception) { w.cancel() }
        return r
    }
}

// ==================================================================== mikrofon

class Mic(private val ctx: Context, private val onChunk: (String, Double) -> Unit) {
    private var rec: AudioRecord? = null
    @Volatile private var on = false
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
            rec = r; on = true
            r.startRecording()
            Thread({
                val buf = ShortArray(1600) // 100 ms
                val bb = ByteBuffer.allocate(3200).order(ByteOrder.LITTLE_ENDIAN)
                while (on) {
                    val n = try { r.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                    if (n <= 0) { if (!on) break; continue }
                    var sum = 0.0
                    bb.clear()
                    for (i in 0 until n) { val s = buf[i]; sum += s.toDouble() * s; bb.putShort(s) }
                    val rms = (sqrt(sum / n) / 6000.0).coerceIn(0.0, 1.0)
                    onChunk(Base64.encodeToString(bb.array(), 0, n * 2, Base64.NO_WRAP), rms)
                }
            }, "koser-mic").start()
            true
        } catch (e: Exception) { false }
    }

    fun stop() {
        on = false
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        try { aec?.release(); ns?.release() } catch (_: Exception) {}
        rec = null
    }
}

// ==================================================================== hoparlör

class Player {
    private data class Seg(val endFrame: Long, val rms: Double)
    private val q = LinkedBlockingQueue<Pair<Int, ByteArray>>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var rate = 24000
    @Volatile private var written = 0L
    @Volatile private var gen = 0
    private val segs = ArrayDeque<Seg>()
    @Volatile private var alive = true

    init {
        Thread({
            while (alive) {
                val (g, data) = try { q.take() } catch (e: InterruptedException) { break }
                if (!alive) break
                if (g != gen) continue
                val t = ensureTrack() ?: continue
                var sum = 0.0
                val n = data.size / 2
                for (i in 0 until n) { val s = ((data[2 * i + 1].toInt() shl 8) or (data[2 * i].toInt() and 0xff)).toShort().toDouble(); sum += s * s }
                val rms = if (n > 0) (sqrt(sum / n) / 7000.0).coerceIn(0.0, 1.0) else 0.0
                try { t.write(data, 0, data.size) } catch (_: Exception) {}
                if (g != gen) continue
                written += n
                synchronized(segs) { segs.addLast(Seg(written, rms)) }
            }
        }, "koser-play").start()
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        return try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min * 2, rate)) // ~0,5 sn
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { it.play(); track = it; written = 0 }
        } catch (e: Exception) { null }
    }

    fun enqueue(pcm: ByteArray, r: Int) {
        if (r != rate) { flush(); rate = r }
        q.put(gen to pcm)
    }

    private fun head(): Long = (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL

    fun isPlaying(): Boolean = q.isNotEmpty() || (track != null && written - head() > rate / 40)

    fun level(): Double {
        val h = head()
        synchronized(segs) {
            while (segs.isNotEmpty() && segs.first().endFrame < h) segs.removeFirst()
            return segs.firstOrNull()?.rms ?: 0.0
        }
    }

    /** Kullanıcı araya girdi: çalanı hemen kes */
    fun flush() {
        gen++
        q.clear()
        val t = track; track = null
        try { t?.pause(); t?.flush(); t?.release() } catch (_: Exception) {}
        written = 0
        synchronized(segs) { segs.clear() }
    }

    fun release() { alive = false; flush(); q.offer(gen to ByteArray(0)) }
}

// ==================================================================== ses yönlendirme (hoparlör + yankı önleme)

object AudioRoute {
    private var prevMode = AudioManager.MODE_NORMAL

    fun enter(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        prevMode = am.mode
        try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
        val headsetTypes = mutableSetOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= 31) headsetTypes.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes }
        if (headset) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } catch (_: Exception) {}
    }

    fun exit(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else { @Suppress("DEPRECATION") am.isSpeakerphoneOn = false }
            am.mode = if (prevMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else prevMode
        } catch (_: Exception) {}
    }
}

// ==================================================================== ön plan servisi (uygulama dışına çıkınca da konuşma sürsün)

class LiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { Live.stop(); stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, 11, notification(),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (e: Exception) {
            Live.stop(); stopSelf(); return START_NOT_STICKY
        }
        if (!Live.running) { stopSelf(); return START_NOT_STICKY }
        Live.begin(this)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, KoserApp.CH_WAKE)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("${Persona.active().name} seninle konuşuyor")
            .setContentText("Konuşma sürüyor. Bitirmek için dokun.")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Bitir", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() { if (Live.running) Live.stop(); super.onDestroy() }
}
