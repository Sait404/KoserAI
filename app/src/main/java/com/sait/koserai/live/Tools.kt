package com.sait.koserai.live

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.sait.koserai.KoserApp
import com.sait.koserai.MainActivity
import com.sait.koserai.R
import com.sait.koserai.core.Apps
import com.sait.koserai.core.Contact
import com.sait.koserai.core.Contacts
import com.sait.koserai.core.Net
import com.sait.koserai.core.Prefs
import com.sait.koserai.core.TextNorm
import com.sait.koserai.core.Weather
import com.sait.koserai.data.CalendarData
import com.sait.koserai.data.Persona
import com.sait.koserai.data.Reminders
import com.sait.koserai.data.Store
import com.sait.koserai.data.When
import com.sait.koserai.tv.TvController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gemini'ye tanıtılan araçlar (function calling) ve telefonda gerçekten çalıştırılmaları. */
object Tools {

    // ------------------------------------------------------------------ bildirimler
    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
    private fun fn(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val pr = JSONObject(); props.forEach { (k, v) -> pr.put(k, v) }
            o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", pr).put("required", JSONArray(required)))
        }
        return o
    }

    fun declarations(): JSONArray = JSONArray().apply {
        put(fn("open_app", "Telefondaki bir uygulamayı adıyla açar (WhatsApp, Instagram, YouTube, kamera, ayarlar...).",
            mapOf("name" to p("STRING", "Uygulamanın adı")), listOf("name")))
        put(fn("send_whatsapp", "Rehberdeki bir kişiye WhatsApp mesajı hazırlar. ÖNCE confirmed=false ile çağır, dönen önizlemeyi kullanıcıya oku ve onay iste. Kullanıcı onaylarsa confirmed=true ile tekrar çağır.",
            mapOf("contact" to p("STRING", "Kişi adı (rehberdeki gibi) veya telefon numarası"), "message" to p("STRING", "Gönderilecek mesaj"),
                "confirmed" to p("BOOLEAN", "Kullanıcı mesajı onayladı mı")), listOf("contact", "message", "confirmed")))
        put(fn("send_sms", "SMS hazırlar. ÖNCE confirmed=false ile çağır ve kullanıcıdan onay al, sonra confirmed=true ile çağır.",
            mapOf("contact" to p("STRING", "Kişi adı veya numara"), "message" to p("STRING", "Mesaj"), "confirmed" to p("BOOLEAN", "Onaylandı mı")),
            listOf("contact", "message", "confirmed")))
        put(fn("call_contact", "Rehberdeki bir kişiyi veya numarayı telefonla arar.", mapOf("contact" to p("STRING", "Kişi adı veya numara")), listOf("contact")))
        put(fn("set_alarm", "Saat alarmı kurar.", mapOf("hour" to p("INTEGER", "0-23"), "minute" to p("INTEGER", "0-59"), "label" to p("STRING", "Alarm adı")), listOf("hour", "minute")))
        put(fn("set_timer", "Geri sayım zamanlayıcısı kurar (ör. makarna için 8 dakika).", mapOf("seconds" to p("INTEGER", "Toplam saniye"), "label" to p("STRING", "Etiket")), listOf("seconds")))
        put(fn("add_calendar_event", "Telefonun takvimine etkinlik/randevu ekler.",
            mapOf("title" to p("STRING", "Başlık"), "start" to p("STRING", "Başlangıç, yerel saat, biçim YYYY-MM-DDTHH:MM"),
                "duration_minutes" to p("INTEGER", "Süre (dakika), varsayılan 60"), "location" to p("STRING", "Yer"),
                "reminder_minutes" to p("INTEGER", "Kaç dakika önce hatırlatılsın, varsayılan 30")), listOf("title", "start")))
        put(fn("get_calendar_events", "Takvimdeki etkinlikleri okur.",
            mapOf("day_offset" to p("INTEGER", "0 bugün, 1 yarın, -1 dün"), "days" to p("INTEGER", "Kaç günlük, varsayılan 1"))))
        put(fn("add_reminder", "Belirli bir zamanda bildirimle hatırlatma kurar.",
            mapOf("text" to p("STRING", "Neyi hatırlatayım"), "at" to p("STRING", "Zaman, yerel saat, YYYY-MM-DDTHH:MM")), listOf("text", "at")))
        put(fn("list_reminders", "Bekleyen hatırlatmaları listeler."))
        put(fn("add_task", "Yapılacaklar listesine görev ekler.", mapOf("title" to p("STRING", "Görev"), "due" to p("STRING", "Son tarih YYYY-MM-DD (isteğe bağlı)")), listOf("title")))
        put(fn("complete_task", "Bir görevi tamamlandı olarak işaretler.", mapOf("title" to p("STRING", "Görevin adı ya da bir kısmı")), listOf("title")))
        put(fn("list_tasks", "Bekleyen görevleri listeler."))
        put(fn("remember", "Kullanıcı hakkında kalıcı bir bilgiyi hafızaya kaydeder (adı, tercihleri, önemli kişiler, tarihler...).",
            mapOf("fact" to p("STRING", "Kısa, tek cümlelik bilgi")), listOf("fact")))
        put(fn("forget", "Hafızadan bir bilgiyi siler.", mapOf("fact" to p("STRING", "Silinecek bilgi")), listOf("fact")))
        put(fn("get_weather", "Hava durumunu verir.", mapOf("city" to p("STRING", "Şehir; boşsa kullanıcının şehri"), "tomorrow" to p("BOOLEAN", "Yarın için mi"))))
        put(fn("navigate", "Haritada yol tarifi başlatır.", mapOf("destination" to p("STRING", "Varış yeri"), "mode" to p("STRING", "driving, walking, transit")), listOf("destination")))
        put(fn("media_control", "Çalan müziği/videoyu kontrol eder.", mapOf("action" to p("STRING", "play, pause, next, previous")), listOf("action")))
        put(fn("youtube_search", "YouTube'da arama açar (şarkı, video).", mapOf("query" to p("STRING", "Aranacak")), listOf("query")))
        put(fn("device_control", "Telefonu kontrol eder: fener ve ses.",
            mapOf("action" to p("STRING", "flashlight_on, flashlight_off, volume_up, volume_down, volume_set, mute, unmute"),
                "level" to p("INTEGER", "volume_set için yüzde 0-100")), listOf("action")))
        put(fn("tv_control", "Kullanıcının televizyonunu kontrol eder.",
            mapOf("action" to p("STRING", "on, off, vol_up, vol_down, mute, ch_up, ch_down, set_vol, home, source"),
                "value" to p("INTEGER", "set_vol için seviye ya da kaç kademe")), listOf("action")))
        put(fn("run_shortcut", "Kullanıcının tanımladığı bir kestirmeyi (ev otomasyonu vb.) çalıştırır.", mapOf("name" to p("STRING", "Kestirmenin adı")), listOf("name")))
        put(fn("get_daily_brief", "Günlük özet için gerçek verileri getirir: bugünkü takvim, görevler, hatırlatmalar, hava ve bugün Koser'in yaptıkları. \"Bugün neler var\", \"bugün ne yaptın\", \"sırada ne var\" gibi sorularda kullan."))
        put(fn("switch_persona", "Karakteri değiştirir (KOSER, ULTRON, NOVA ya da kullanıcının verdiği ad).", mapOf("persona" to p("STRING", "Karakter adı")), listOf("persona")))
        put(fn("camera_vision", "Kamerayı açıp kapatır; açıkken kullanıcının gösterdiğini görürsün (yazı okuma, nesne tanıma).", mapOf("enabled" to p("BOOLEAN", "Açılsın mı")), listOf("enabled")))
        put(fn("get_device_status", "Pil durumu, saat ve tarih."))
        put(fn("end_conversation", "Kullanıcı konuşmayı bitirmek istediğinde (görüşürüz, tamam bu kadar) konuşmayı kapatır."))
    }

    // ------------------------------------------------------------------ çalıştırma

    private fun ok(msg: String) = JSONObject().put("result", msg)
    private fun err(msg: String) = JSONObject().put("error", msg)

    suspend fun run(ctx: Context, name: String, a: JSONObject): JSONObject { return try {
        when (name) {
            "open_app" -> withContext(Dispatchers.Main) { openApp(ctx, a.optString("name")) }
            "send_whatsapp" -> withContext(Dispatchers.Main) { message(ctx, a, whatsapp = true) }
            "send_sms" -> withContext(Dispatchers.Main) { message(ctx, a, whatsapp = false) }
            "call_contact" -> withContext(Dispatchers.Main) { call(ctx, a.optString("contact")) }
            "set_alarm" -> withContext(Dispatchers.Main) {
                val h = a.optInt("hour"); val m = a.optInt("minute")
                launch(ctx, Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label").ifBlank { "Koser" }).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Alarm")
                Store.log("alarm", "Saat %02d:%02d için alarm kurdum".format(h, m))
                ok("Alarm %02d:%02d için kuruldu.".format(h, m))
            }
            "set_timer" -> withContext(Dispatchers.Main) {
                val s = a.optInt("seconds", 60).coerceIn(1, 86_399)
                launch(ctx, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label").ifBlank { "Koser" }).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Zamanlayıcı")
                Store.log("timer", "${s / 60} dakika ${s % 60} saniyelik zamanlayıcı kurdum")
                ok("Zamanlayıcı $s saniye için kuruldu.")
            }
            "add_calendar_event" -> withContext(Dispatchers.IO) { addEvent(ctx, a) }
            "get_calendar_events" -> withContext(Dispatchers.IO) {
                if (!CalendarData.canRead(ctx)) err("Takvim izni verilmemiş. Kullanıcıya Ayarlar > İzinler bölümünden takvim iznini açmasını söyle.")
                else {
                    val ev = CalendarData.events(ctx, a.optInt("day_offset", 0), a.optInt("days", 1))
                    JSONObject().put("events", ev).put("count", ev.length())
                }
            }
            "add_reminder" -> {
                val at = When.parse(a.optString("at")) ?: return err("Zaman anlaşılamadı. Biçim YYYY-MM-DDTHH:MM olmalı.")
                if (at < System.currentTimeMillis() - 60_000) return err("Bu zaman geçmişte kalıyor.")
                val r = Store.addReminder(a.optString("text"), at)
                Reminders.schedule(ctx, r)
                Store.log("reminder", "${When.human(at)} için hatırlatma kurdum: ${a.optString("text")}")
                ok("Hatırlatma kuruldu: ${When.human(at)}")
            }
            "list_reminders" -> JSONObject().put("reminders", JSONArray(Store.pendingReminders().map { JSONObject().put("text", it.optString("text")).put("when", When.human(it.optLong("at"))) }))
            "add_task" -> {
                val t = Store.addTask(a.optString("title"), a.optString("due").takeIf { it.isNotBlank() })
                Store.log("task", "Görev ekledim: ${t.optString("title")}")
                ok("Görev eklendi.")
            }
            "complete_task" -> {
                val t = Store.completeTask(a.optString("title")) ?: return err("Böyle bekleyen bir görev bulamadım.")
                Store.log("task_done", "Görevi tamamlandı olarak işaretledim: ${t.optString("title")}")
                ok("\"${t.optString("title")}\" tamamlandı.")
            }
            "list_tasks" -> JSONObject().put("tasks", JSONArray(Store.openTasks().map { JSONObject().put("title", it.optString("title")).put("due", it.optString("due")) }))
            "remember" -> { Store.remember(a.optString("fact")); ok("Hafızaya kaydedildi.") }
            "forget" -> { val m = Store.forget(a.optString("fact")); if (m == null) err("Hafızada böyle bir bilgi yok.") else ok("Silindi: ${m.optString("text")}") }
            "get_weather" -> withContext(Dispatchers.IO) {
                ok(Weather.describe(a.optString("city").ifBlank { Prefs.str("city").ifBlank { "İstanbul" } }, a.optBoolean("tomorrow")))
            }
            "navigate" -> withContext(Dispatchers.Main) {
                val mode = when (a.optString("mode")) { "walking" -> "w"; "transit" -> "r"; else -> "d" }
                val dest = a.optString("destination")
                if (!tryLaunch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$mode"))))
                    launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))), "Harita")
                Store.log("nav", "$dest için yol tarifi açtım")
                ok("Yol tarifi açıldı.")
            }
            "media_control" -> withContext(Dispatchers.Main) { media(ctx, a.optString("action")) }
            "youtube_search" -> withContext(Dispatchers.Main) {
                val q = a.optString("query")
                val i = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
                if (!tryLaunch(ctx, i)) launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Net.enc(q))), "YouTube")
                Store.log("youtube", "YouTube'da \"$q\" açtım")
                ok("YouTube'da arama açıldı.")
            }
            "device_control" -> withContext(Dispatchers.Main) { device(ctx, a.optString("action"), if (a.has("level")) a.optInt("level") else null) }
            "tv_control" -> {
                val r = TvController.run(ctx, a.optString("action"), if (a.has("value") && !a.isNull("value")) a.optInt("value") else null)
                Store.log("tv", r); ok(r)
            }
            "run_shortcut" -> withContext(Dispatchers.IO) { Shortcuts.run(a.optString("name")) }
            "get_daily_brief" -> withContext(Dispatchers.IO) { brief(ctx) }
            "switch_persona" -> {
                val pp = Persona.find(a.optString("persona")) ?: return err("Böyle bir karakter yok. Seçenekler: " + Persona.all().joinToString { it.name })
                Prefs.put("persona", pp.id)
                Live.requestRestart()
                ok("Karakter ${pp.name} olarak değişti. Bu cevaptan sonra yeni sesle devam edilecek; kısa bir cümleyle haber ver.")
            }
            "camera_vision" -> { Live.setVision(a.optBoolean("enabled")); ok(if (a.optBoolean("enabled")) "Kamera açıldı, saniyede bir kare görüyorsun." else "Kamera kapatıldı.") }
            "get_device_status" -> {
                val bm = ctx.getSystemService(BatteryManager::class.java)
                JSONObject().put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).put("charging", bm.isCharging)
                    .put("now", SimpleDateFormat("d MMMM yyyy EEEE HH:mm", Locale("tr")).format(Date()))
            }
            "end_conversation" -> { Live.requestEnd(); ok("Konuşma, bu cevaptan sonra kapanacak. Kısa bir vedalaş.") }
            else -> err("Bilinmeyen araç: $name")
        }
    } catch (e: SecurityException) {
        err("Gerekli izin yok: ${e.message}")
    } catch (e: Exception) {
        err(e.message ?: "Beklenmeyen hata")
    } }

    // ------------------------------------------------------------------ uygulama başlatma (arka plandaysak bildirimle)

    private fun canStartDirectly(ctx: Context) = MainActivity.isForeground || Settings.canDrawOverlays(ctx)

    /** Doğrudan açar; uygulama arka plandaysa ve izin yoksa dokunulabilir bildirim gösterir. */
    fun launch(ctx: Context, i: Intent, label: String): Boolean {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (canStartDirectly(ctx)) { ctx.startActivity(i); return true }
        val pi = PendingIntent.getActivity(ctx, label.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ctx.getSystemService(NotificationManager::class.java).notify(label.hashCode(),
            NotificationCompat.Builder(ctx, KoserApp.CH_ALERT).setSmallIcon(R.drawable.ic_fg)
                .setContentTitle("Koser: $label").setContentText("Açmak için dokun").setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).build())
        return false
    }

    private fun tryLaunch(ctx: Context, i: Intent): Boolean = try {
        if (i.resolveActivity(ctx.packageManager) == null && i.`package` != null) false else { launch(ctx, i, "Aç"); true }
    } catch (e: ActivityNotFoundException) { false }

    private fun bgNote(started: Boolean) = if (started) "" else " (Kullanıcı başka uygulamadaydı; açması için bildirim gönderildi.)"

    private fun openApp(ctx: Context, name: String): JSONObject {
        val t = Apps.find(ctx, name) ?: return err("\"$name\" adında yüklü uygulama bulunamadı.")
        return when (t) {
            is Apps.Target.Pkg -> {
                val i = ctx.packageManager.getLaunchIntentForPackage(t.entry.pkg) ?: return err("${t.entry.label} açılamıyor.")
                val s = launch(ctx, i, t.entry.label)
                Store.log("app", "${t.entry.label} uygulamasını açtım")
                ok("${t.entry.label} açıldı." + bgNote(s))
            }
            is Apps.Target.Sys -> {
                val i = when (t.kind) {
                    "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    "settings" -> Intent(Settings.ACTION_SETTINGS)
                    "dialer" -> Intent(Intent.ACTION_DIAL)
                    "clock" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
                    "gallery" -> Intent(Intent.ACTION_VIEW).setType("image/*")
                    "calculator" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
                    "calendar" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                    "contacts" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
                    "sms" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
                    "browser" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
                    "music" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
                    else -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES)
                }
                val s = launch(ctx, i, t.label)
                Store.log("app", "${TextNorm.capitalizeTr(t.label)} açtım")
                ok("${t.label} açıldı." + bgNote(s))
            }
        }
    }

    // ------------------------------------------------------------------ kişiler

    private sealed class Who { data class One(val c: Contact) : Who(); data class Many(val names: List<String>) : Who(); object None : Who(); object NoPerm : Who() }

    private fun resolve(ctx: Context, q: String): Who {
        val digits = q.filter { it.isDigit() }
        if (digits.length >= 7) return Who.One(Contact(q, q, emptyList()))
        if (!Contacts.hasPermission(ctx)) return Who.NoPerm
        val toks = q.split(Regex("[\\s'’]+")).map { TextNorm.basic(it) }.filter { it.isNotBlank() }
        val hit = Contacts.findIn(ctx, toks) ?: return Who.None
        // aynı ilk adı taşıyan birden çok kişi var mı?
        val first = hit.contact.tokens.firstOrNull() ?: ""
        if (toks.size == 1) {
            val same = Contacts.all(ctx).filter { it.tokens.firstOrNull() == first }
            if (same.size > 1 && same.none { it.tokens.size == 1 }) return Who.Many(same.take(4).map { it.name })
        }
        return Who.One(hit.contact)
    }

    private fun whoErr(w: Who, q: String): JSONObject = when (w) {
        is Who.NoPerm -> err("Rehber izni yok. Kullanıcıya Ayarlar > İzinler'den rehber iznini açmasını söyle.")
        is Who.None -> err("Rehberde \"$q\" bulunamadı.")
        is Who.Many -> JSONObject().put("ambiguous", true).put("candidates", JSONArray(w.names)).put("instruction", "Kullanıcıya hangisini kastettiğini sor.")
        else -> err("?")
    }

    private fun message(ctx: Context, a: JSONObject, whatsapp: Boolean): JSONObject {
        val q = a.optString("contact"); val msg = a.optString("message").trim()
        if (msg.isBlank()) return err("Mesaj metni boş. Kullanıcıya ne yazılacağını sor.")
        val w = resolve(ctx, q)
        if (w !is Who.One) return whoErr(w, q)
        val c = w.c
        if (!a.optBoolean("confirmed")) {
            return JSONObject().put("needs_confirmation", true).put("to", c.name).put("message", msg)
                .put("instruction", "Kullanıcıya şunu sor: '${c.name} kişisine \"$msg\" yazayım mı?' Onaylarsa confirmed=true ile tekrar çağır.")
        }
        if (whatsapp) {
            val pkg = Apps.whatsappPackage(ctx) ?: return err("Telefonda WhatsApp yüklü değil.")
            val num = Contacts.toInternational(c.number)
            val s = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$num&text=" + Net.enc(msg))).setPackage(pkg), "WhatsApp")
            Store.log("whatsapp", "${c.name} kişisine WhatsApp mesajı hazırladım: \"$msg\"")
            return ok("WhatsApp'ta ${c.name} sohbeti mesaj yazılı halde açıldı. Kullanıcının yalnızca Gönder'e basması gerekiyor." + bgNote(s))
        }
        val s = launch(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", msg), "SMS")
        Store.log("sms", "${c.name} kişisine SMS hazırladım: \"$msg\"")
        return ok("SMS ekranı mesaj yazılı halde açıldı; kullanıcı Gönder'e basacak." + bgNote(s))
    }

    private fun call(ctx: Context, q: String): JSONObject {
        val w = resolve(ctx, q)
        if (w !is Who.One) return whoErr(w, q)
        val can = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val s = launch(ctx, Intent(if (can) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(w.c.number))), "Arama")
        Store.log("call", "${w.c.name} kişisini aradım")
        return ok((if (can) "${w.c.name} aranıyor." else "${w.c.name} numarası tuşlandı; aramak için yeşil tuşa basması gerekiyor.") + bgNote(s))
    }

    // ------------------------------------------------------------------ takvim

    private fun addEvent(ctx: Context, a: JSONObject): JSONObject {
        val start = When.parse(a.optString("start")) ?: return err("Başlangıç zamanı anlaşılamadı (YYYY-MM-DDTHH:MM).")
        val title = a.optString("title").ifBlank { "Etkinlik" }
        val dur = a.optInt("duration_minutes", 60).coerceIn(5, 24 * 60)
        val rem = if (a.has("reminder_minutes")) a.optInt("reminder_minutes") else 30
        val id = try { CalendarData.insert(ctx, title, start, dur, a.optString("location"), rem) } catch (e: Exception) { null }
        return if (id != null) {
            Store.log("calendar", "Takvime ekledim: $title (${When.human(start)})")
            ok("Takvime eklendi: $title, ${When.human(start)}.")
        } else {
            val s = launch(ctx, CalendarData.insertIntent(title, start, dur, a.optString("location")), "Takvim")
            Store.log("calendar", "Takvim ekranını açtım: $title")
            ok("Takvime doğrudan yazamadım (izin ya da takvim hesabı yok); takvim ekranı dolu halde açıldı, kullanıcı Kaydet'e basmalı." + bgNote(s))
        }
    }

    // ------------------------------------------------------------------ cihaz

    private fun media(ctx: Context, action: String): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return ok("Tamam.")
    }

    private fun device(ctx: Context, action: String, level: Int?): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        when (action) {
            "flashlight_on", "flashlight_off" -> {
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return err("Fener yok.")
                cm.setTorchMode(id, action == "flashlight_on")
                return ok(if (action == "flashlight_on") "Fener açıldı." else "Fener kapandı.")
            }
            "volume_up" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "volume_down" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "volume_set" -> am.setStreamVolume(s, ((level ?: 50).coerceIn(0, 100) * max / 100), AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            else -> return err("Bilinmeyen işlem.")
        }
        return ok("Medya sesi yüzde ${am.getStreamVolume(s) * 100 / maxOf(1, max)}.")
    }

    // ------------------------------------------------------------------ günlük özet (gerçek veriler)

    private suspend fun brief(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("now", SimpleDateFormat("d MMMM yyyy EEEE HH:mm", Locale("tr")).format(Date()))
        o.put("weather", try { Weather.describe(Prefs.str("city").ifBlank { "İstanbul" }, false) } catch (e: Exception) { "Hava durumuna ulaşılamadı." })
        if (CalendarData.canRead(ctx)) {
            o.put("today_events", CalendarData.events(ctx, 0, 1))
            o.put("tomorrow_events", CalendarData.events(ctx, 1, 1))
        } else o.put("calendar", "Takvim izni yok")
        o.put("open_tasks", JSONArray(Store.openTasks().map { JSONObject().put("title", it.optString("title")).put("due", it.optString("due")) }))
        val endToday = Store.startOfDay(1)
        o.put("reminders_today", JSONArray(Store.pendingReminders().filter { it.optLong("at") < endToday }.map { JSONObject().put("text", it.optString("text")).put("when", When.human(it.optLong("at"))) }))
        o.put("koser_did_today", JSONArray(Store.logToday().map { it.optString("text") }))
        o.put("instruction", "Bunu doğal, kısa bir sesli özet olarak anlat: önce en önemli/yakın şey, sonra kalanlar, sonra bugün senin yaptıkların. Boş olan bölümleri tek cümleyle geç.")
        return o
    }
}

/** Kullanıcının kendi kestirmeleri: ad + "YÖNTEM adres gövde" */
object Shortcuts {
    fun list(): List<JSONObject> {
        val a = Prefs.all().optJSONArray("shortcuts") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    fun run(name: String): JSONObject {
        val n = TextNorm.basic(name)
        val sc = list().maxByOrNull { TextNorm.similarity(TextNorm.basic(it.optString("name")), n) }
            ?.takeIf { TextNorm.similarity(TextNorm.basic(it.optString("name")), n) >= 0.55 || TextNorm.basic(it.optString("name")).contains(n) }
            ?: return JSONObject().put("error", "\"$name\" adında kestirme yok. Mevcutlar: " + list().joinToString { it.optString("name") }.ifBlank { "hiç yok (Ayarlar > Kestirmeler'den eklenebilir)" })
        return try {
            exec(sc.optString("action"), sc.optString("header"))
            Store.log("shortcut", "\"${sc.optString("name")}\" kestirmesini çalıştırdım")
            JSONObject().put("result", "\"${sc.optString("name")}\" çalıştırıldı.")
        } catch (e: Exception) { JSONObject().put("error", "Kestirme çalışmadı: ${e.message}") }
    }

    fun exec(line: String, header: String) {
        val m = Regex("^(GET|POST|PUT|PATCH|DELETE)\\s+(\\S+)\\s*(.*)$", RegexOption.IGNORE_CASE).find(line.trim())
        val method = m?.groupValues?.get(1)?.uppercase() ?: "GET"
        val url = m?.groupValues?.get(2) ?: line.trim()
        val body = m?.groupValues?.get(3)?.takeIf { it.isNotBlank() }
        val rb = Request.Builder().url(url)
        header.split(";", "\n").map { it.trim() }.filter { it.contains(":") }.forEach { rb.header(it.substringBefore(":").trim(), it.substringAfter(":").trim()) }
        val ct = if (body?.trimStart()?.startsWith("{") == true) "application/json" else "text/plain"
        when (method) {
            "GET" -> rb.get(); "DELETE" -> rb.delete()
            else -> rb.method(method, (body ?: "").toRequestBody(ct.toMediaType()))
        }
        Net.http.newCall(rb.build()).execute().use { r -> if (!r.isSuccessful) throw RuntimeException("sunucu ${r.code} döndü") }
    }
}
