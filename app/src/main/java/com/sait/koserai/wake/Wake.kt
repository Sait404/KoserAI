package com.sait.koserai.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sait.koserai.KoserApp
import com.sait.koserai.MainActivity
import com.sait.koserai.R
import com.sait.koserai.assist.KoserVoiceService
import com.sait.koserai.core.Net
import com.sait.koserai.core.Prefs
import com.sait.koserai.core.TextNorm
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

// ================================================================ Model (çevrimdışı Türkçe tanıma, ~35 MB)

object VoskModel {
    const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"
    @Volatile private var model: Model? = null
    @Volatile var downloading = false

    fun dir(ctx: Context) = File(ctx.filesDir, "vosk-tr")
    fun isReady(ctx: Context) = File(dir(ctx), "am").exists() || File(dir(ctx), "conf").exists()

    @Synchronized
    fun get(ctx: Context): Model? {
        model?.let { return it }
        if (!isReady(ctx)) return null
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(dir(ctx).absolutePath).also { model = it }
        } catch (e: Throwable) { null }
    }

    /** progress: 0..100, bitince done(null) ya da done(hata) */
    fun download(ctx: Context, progress: (Int) -> Unit, done: (String?) -> Unit) {
        if (downloading) return
        downloading = true
        thread(name = "vosk-dl") {
            val tmpZip = File(ctx.cacheDir, "vosk.zip")
            val tmpDir = File(ctx.filesDir, "vosk-tmp")
            try {
                Net.http.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("İndirme hatası (${r.code})")
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: 40_000_000L
                    var read = 0L; var last = -1
                    body.byteStream().use { inp ->
                        tmpZip.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); read += n
                                val p = (read * 90 / total).toInt().coerceAtMost(90)
                                if (p != last) { last = p; progress(p) }
                            }
                        }
                    }
                }
                tmpDir.deleteRecursively(); tmpDir.mkdirs()
                ZipInputStream(tmpZip.inputStream().buffered()).use { zis ->
                    while (true) {
                        val e = zis.nextEntry ?: break
                        val f = File(tmpDir, e.name)
                        if (!f.canonicalPath.startsWith(tmpDir.canonicalPath)) continue // güvenlik
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zis.copyTo(it) } }
                    }
                }
                progress(97)
                val root = tmpDir.listFiles()?.firstOrNull { it.isDirectory && File(it, "conf").exists() } ?: tmpDir
                dir(ctx).deleteRecursively()
                if (!root.renameTo(dir(ctx))) { root.copyRecursively(dir(ctx), true) }
                tmpDir.deleteRecursively(); tmpZip.delete()
                progress(100)
                done(null)
            } catch (e: Exception) {
                tmpZip.delete(); tmpDir.deleteRecursively()
                done(e.message ?: "İndirilemedi")
            } finally { downloading = false }
        }
    }

    fun delete(ctx: Context) { synchronized(this) { model?.close(); model = null }; dir(ctx).deleteRecursively() }
}

// ================================================================ Eşleştirme

object WakeMatcher {
    private fun threshold() = when (Prefs.int("wakeSensitivity")) { 1 -> 0.8; 3 -> 0.55; else -> 0.66 }

    fun matches(textRaw: String): Boolean {
        val text = TextNorm.basic(textRaw)
        if (text.isBlank()) return false
        val aliases = Prefs.all().optJSONArray("wakeAliases") ?: JSONArray()
        for (i in 0 until aliases.length()) {
            val a = aliases.optString(i)
            if (a.length >= 3 && (" $text ").contains(" $a ")) return true
        }
        val phrase = TextNorm.basic(Prefs.str("wakePhrase").ifBlank { "hey koser" }).split(" ").filter { it.isNotBlank() }
        val words = text.split(" ")
        val ki = phrase.indexOf(Prefs.WAKE_REQUIRED_WORD)
        val thr = threshold()
        val joined = text.replace(" ", "")
        if (joined.contains(phrase.joinToString(""))) return true
        for (i in words.indices) {
            val w = words[i]
            val simK = maxOf(TextNorm.similarity(w, "koser"), TextNorm.similarity(w, "kozer"), if (w.endsWith("koser")) 1.0 else 0.0)
            if (simK < thr) continue
            if (phrase.size == 1) return true
            // cümlenin diğer kelimeleri de yaklaşık doğru sırada olmalı (yanlış uyanmayı azaltır)
            var ok = 0; var need = 0
            for ((j, pw) in phrase.withIndex()) {
                if (j == ki) continue
                need++
                val pos = i + (j - ki)
                val cand = listOfNotNull(words.getOrNull(pos), words.getOrNull(pos - 1), words.getOrNull(pos + 1))
                if (cand.any { TextNorm.similarity(it, pw) >= 0.6 || (pw.length <= 3 && it.length <= 4 && it.firstOrNull() == pw.firstOrNull() ) }) ok++
            }
            if (need == 0 || ok >= (need + 1) / 2) return true
        }
        return false
    }
}

// ================================================================ Motor

class WakeEngine(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
) {
    private var svc: SpeechService? = null
    private var rec: Recognizer? = null

    fun start(): Boolean {
        if (svc != null) return true
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val model = VoskModel.get(ctx) ?: return false
        return try {
            val r = Recognizer(model, 16000f)
            rec = r
            svc = SpeechService(r, 16000f).also {
                it.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) { field(hypothesis, "partial")?.let(onPartial) }
                    override fun onResult(hypothesis: String?) { field(hypothesis, "text")?.let(onFinal) }
                    override fun onFinalResult(hypothesis: String?) { field(hypothesis, "text")?.let(onFinal) }
                    override fun onError(exception: Exception?) { stop() }
                    override fun onTimeout() {}
                })
            }
            true
        } catch (e: Throwable) { stop(); false }
    }

    private fun field(json: String?, key: String): String? =
        try { JSONObject(json ?: "{}").optString(key).takeIf { it.isNotBlank() } } catch (e: Exception) { null }

    fun stop() {
        try { svc?.stop(); svc?.shutdown() } catch (_: Throwable) {}
        svc = null
        try { rec?.close() } catch (_: Throwable) {}
        rec = null
    }

    val running get() = svc != null
}

// ================================================================ Arka plan servisi

class WakeWordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var engine: WakeEngine? = null
    private var lastTrigger = 0L
    private var pausedByUi = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.put("wakeEnabled", false)
            stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            notifyNeedOpen(this)
            stopSelf(); return START_NOT_STICKY
        }
        running = true
        thread { VoskModel.get(this); main.post { if (!pausedByUi) startEngine() } }
        return START_STICKY
    }

    private fun startEngine() {
        if (engine?.running == true) return
        val e = WakeEngine(this, onPartial = { check(it) }, onFinal = { check(it) })
        if (e.start()) engine = e
    }

    private fun check(text: String) {
        if (System.currentTimeMillis() - lastTrigger < 3000) return
        if (!WakeMatcher.matches(text)) return
        lastTrigger = System.currentTimeMillis()
        engine?.stop(); engine = null
        pausedByUi = true
        trigger()
        // güvenlik: arayüz hiç açılamazsa dinlemeye geri dön
        main.postDelayed({ if (pausedByUi && !MainActivity.isForeground) { pausedByUi = false; startEngine() } }, 20_000)
    }

    private fun trigger() {
        try {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
        if (MainActivity.isForeground) { MainActivity.wakeFromService(); return }
        if (KoserVoiceService.summon()) return
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_LISTEN, true))
                return
            } catch (_: Exception) {}
        }
        val pi = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_LISTEN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(NotificationManager::class.java).notify(3, NotificationCompat.Builder(this, KoserApp.CH_ALERT)
            .setSmallIcon(R.drawable.ic_fg).setContentTitle("Koser seni duydu")
            .setContentText("Konuşmak için dokun. (Arka planda açılabilmesi için Koser'i varsayılan asistan yap.)")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build())
        main.postDelayed({ pausedByUi = false; startEngine() }, 1500)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val phrase = TextNorm.capitalizeTr(Prefs.str("wakePhrase").ifBlank { "hey koser" })
        return NotificationCompat.Builder(this, KoserApp.CH_WAKE)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Koser dinlemede")
            .setContentText("Uyandırmak için \"$phrase\" de. Ses telefonda işlenir, kaydedilmez.")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Durdur", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        engine?.stop(); engine = null
        running = false
        instance = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.sait.koserai.STOP_WAKE"
        const val NOTIF_ID = 7
        @Volatile var instance: WakeWordService? = null
        @Volatile var running = false

        fun tryStart(ctx: Context): Boolean {
            if (!VoskModel.isReady(ctx)) return false
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            return try { ctx.startForegroundService(Intent(ctx, WakeWordService::class.java)); true } catch (e: Exception) { false }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeWordService::class.java)) }

        /** Arayüz mikrofonu kullanacakken */
        fun pause() { instance?.let { s -> s.main.post { s.pausedByUi = true; s.engine?.stop(); s.engine = null } } }

        /** Arayüz işini bitirince */
        fun resume() { instance?.let { s -> s.main.post { s.pausedByUi = false; s.startEngine() } } }

        fun notifyNeedOpen(ctx: Context) {
            val pi = PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            ctx.getSystemService(NotificationManager::class.java).notify(5, NotificationCompat.Builder(ctx, KoserApp.CH_ALERT)
                .setSmallIcon(R.drawable.ic_fg).setContentTitle("\"Hey Koser\" beklemede")
                .setContentText("Arka planda dinlemeyi başlatmak için Koser'i bir kez aç.")
                .setAutoCancel(true).setContentIntent(pi).build())
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { com.sait.koserai.data.Reminders.rescheduleAll(context) } catch (_: Exception) {}
        if (!Prefs.bool("wakeEnabled")) return
        if (!WakeWordService.tryStart(context)) WakeWordService.notifyNeedOpen(context)
    }
}

// ================================================================ Öğretme (kullanıcının sesini tanı)

class WakeTrainer(private val ctx: Context, private val event: (JSONObject) -> Unit) {
    private var engine: WakeEngine? = null
    private val heard = ArrayList<String>()
    private val main = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    fun start() {
        if (!VoskModel.isReady(ctx)) { event(JSONObject().put("step", -1).put("msg", "Önce ses modelini indir.")); return }
        WakeWordService.pause()
        heard.clear()
        main.post { listenNext() }
    }

    private fun listenNext() {
        event(JSONObject().put("step", heard.size + 1).put("msg", "Şimdi \"${TextNorm.capitalizeTr(Prefs.str("wakePhrase"))}\" de (${heard.size + 1}/3)"))
        val e = WakeEngine(ctx, onPartial = { p -> event(JSONObject().put("partial", p)) }, onFinal = { f -> gotFinal(f) })
        if (!e.start()) { event(JSONObject().put("step", -1).put("msg", "Mikrofon açılamadı.")); return }
        engine = e
        timeout = Runnable { finish(false) }.also { main.postDelayed(it, 12_000) }
    }

    private fun gotFinal(text: String) {
        val n = TextNorm.basic(text)
        if (n.isBlank()) return
        timeout?.let { main.removeCallbacks(it) }
        engine?.stop(); engine = null
        heard.add(n)
        if (heard.size >= 3) finish(true) else main.postDelayed({ listenNext() }, 500)
    }

    private fun finish(ok: Boolean) {
        engine?.stop(); engine = null
        timeout?.let { main.removeCallbacks(it) }
        if (ok) {
            val arr = Prefs.all().optJSONArray("wakeAliases") ?: JSONArray()
            val existing = (0 until arr.length()).map { arr.optString(it) }.toMutableSet()
            heard.filter { it.length >= 3 }.forEach { existing.add(it) }
            Prefs.put("wakeAliases", JSONArray(existing.toList().takeLast(12)))
            event(JSONObject().put("step", 4).put("msg", "Öğrendim! Sesini şöyle duydum: " + heard.joinToString(" · ")))
        } else {
            event(JSONObject().put("step", -1).put("msg", "Bir şey duyamadım. Tekrar dene."))
        }
        WakeWordService.resume()
    }

    fun cancel() { finish(false) }
}
