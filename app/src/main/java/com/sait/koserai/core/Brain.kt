package com.sait.koserai.core

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import com.sait.koserai.tv.TvController
import com.sait.koserai.tv.TvException
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** say: söylenecek söz, listenAgain: cevaptan sonra tekrar dinle, ui: arayüze özel olay */
data class Reply(val say: String, val listenAgain: Boolean = false, val ui: JSONObject? = null)

class Brain(private val act: Activity, private val onAsync: (Reply) -> Unit) {
    private val ctx: Context get() = act
    private var pending: (suspend (String) -> Reply)? = null
    private val tr = Locale("tr")
    private val user get() = Prefs.str("userName").ifBlank { "efendim" }

    fun cancelPending() { pending = null }

    suspend fun handle(textRaw: String): Reply {
        val text = stripWake(textRaw)
        pending?.let { p ->
            pending = null
            val n = TextNorm.basic(text)
            if (n in setOf("iptal", "vazgec", "bosver", "bos ver", "bir sey yok", "yok")) return Reply("Tamam, iptal ettim.")
            return p(text)
        }
        if (text.isBlank()) return Reply("Efendim $user?", listenAgain = true)
        return exec(CommandParser.parse(text), text)
    }

    /** Baştaki "hey koser" kısmını at */
    private fun stripWake(s: String): String {
        val phrase = TextNorm.basic(Prefs.str("wakePhrase")).split(" ").toSet() + setOf("koser", "hey", "hay", "ey")
        val parts = s.trim().split(Regex("\\s+")).toMutableList()
        while (parts.isNotEmpty()) {
            val b = TextNorm.basic(parts[0])
            if (b in phrase || TextNorm.similarity(b, "koser") >= 0.8) parts.removeAt(0) else break
        }
        return parts.joinToString(" ").trim(' ', ',', '.')
    }

    private suspend fun exec(cmd: Cmd, text: String): Reply = try {
        when (cmd) {
            is Cmd.WhoAreYou -> Reply("Ben KoserAI. Beni Sait geliştirdi. Sana nasıl yardımcı olabilirim, $user?")
            is Cmd.Stop -> Reply("", ui = JSONObject().put("type", "stop"))
            is Cmd.Theme -> {
                Prefs.put("theme", cmd.id)
                val say = when (cmd.id) { "kizil" -> "Kızıl mod etkin. Sistemler tam güçte."; "altin" -> "Altın mod etkin."; else -> "Normal moda döndüm." }
                Reply(say, ui = JSONObject().put("type", "theme").put("id", cmd.id))
            }
            is Cmd.OpenApp -> openApp(cmd.name)
            is Cmd.WhatsApp -> whatsappFromSentence(cmd.raw)
            is Cmd.WhatsAppTo -> whatsappTo(cmd.contact, cmd.text)
            is Cmd.Call -> call(cmd.raw)
            is Cmd.Sms -> sms(cmd.raw)
            is Cmd.Alarm -> {
                start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, cmd.hour).putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, cmd.label ?: "Koser").putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                Reply("Alarmı saat ${"%02d:%02d".format(cmd.hour, cmd.minute)} için kurdum.")
            }
            is Cmd.Timer -> {
                start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Koser").putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                Reply("${durationText(cmd.seconds)} sonrası için zamanlayıcı kurdum.")
            }
            is Cmd.Torch -> torch(cmd.on)
            is Cmd.Volume -> volume(cmd.op, cmd.level, text)
            is Cmd.Media -> media(cmd.op)
            is Cmd.YouTube -> youtube(cmd.query)
            is Cmd.Search -> {
                val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, cmd.query)
                if (!tryStart(i)) start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Net.enc(cmd.query))))
                Reply("${cmd.query} için arama yapıyorum.")
            }
            is Cmd.Navigate -> {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Net.enc(cmd.dest)))
                if (!tryStart(i)) start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Net.enc(cmd.dest))))
                Reply("${cmd.dest} için yol tarifi başlatıyorum.")
            }
            is Cmd.Time -> Reply("Saat " + SimpleDateFormat("HH:mm", tr).format(Date()) + ".")
            is Cmd.Date -> Reply("Bugün " + SimpleDateFormat("d MMMM yyyy, EEEE", tr).format(Date()) + ".")
            is Cmd.Battery -> battery()
            is Cmd.Weather -> Reply(Weather.describe(cmd.city ?: Prefs.str("city").ifBlank { "İstanbul" }, cmd.tomorrow))
            is Cmd.Tv -> Reply(TvController.run(ctx, cmd.op, cmd.value))
            is Cmd.Global -> global(cmd.op)
            is Cmd.Note -> {
                if (cmd.text.isBlank()) { pending = { t -> exec(Cmd.Note(t), t) }; Reply("Ne not alayım?", listenAgain = true) }
                else {
                    val arr = Prefs.all().optJSONArray("notes") ?: JSONArray()
                    arr.put(JSONObject().put("t", System.currentTimeMillis()).put("text", cmd.text))
                    Prefs.put("notes", arr)
                    Reply("Not aldım: ${cmd.text}")
                }
            }
            is Cmd.ReadNotes -> {
                val arr = Prefs.all().optJSONArray("notes") ?: JSONArray()
                if (arr.length() == 0) Reply("Hiç notun yok.")
                else Reply("${arr.length()} notun var. " + (0 until arr.length()).toList().takeLast(5).mapIndexed { i, k -> "${i + 1}: ${arr.getJSONObject(k).optString("text")}" }.joinToString(". ") + ".")
            }
            is Cmd.ClearNotes -> { Prefs.put("notes", JSONArray()); Reply("Tüm notlarını sildim.") }
            is Cmd.SettingsPanel -> settingsPanel(cmd.which)
            is Cmd.Camera -> {
                val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                if (cmd.selfie) i.putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extras.LENS_FACING_FRONT", 1).putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                start(i); Reply(if (cmd.selfie) "Ön kamerayı açıyorum." else "Kamerayı açıyorum.")
            }
            is Cmd.Calc -> Calc.eval(cmd.expr)?.let { Reply("Sonuç ${Calc.say(it)}.") } ?: chat(text)
            is Cmd.Coin -> Reply(if (Math.random() < 0.5) "Yazı geldi." else "Tura geldi.")
            is Cmd.Dice -> Reply("Zar ${(1..6).random()} geldi.")
            is Cmd.Chat -> chat(cmd.text)
        }
    } catch (e: TvException) {
        Reply(e.message ?: "Televizyona ulaşamadım.")
    } catch (e: ActivityNotFoundException) {
        Reply("Bunu yapacak bir uygulama telefonunda bulamadım.")
    } catch (e: SecurityException) {
        Reply("Bunun için gereken izin verilmemiş.")
    } catch (e: Exception) {
        Reply("Bir sorun çıktı: ${e.message ?: "bilinmeyen hata"}")
    }

    // ------------------------------------------------------------------ uygulamalar

    private fun start(i: Intent) { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun tryStart(i: Intent): Boolean = try { start(i); true } catch (e: ActivityNotFoundException) { false }

    private fun openApp(name: String): Reply {
        val target = Apps.find(ctx, name) ?: return Reply("${TextNorm.capitalizeTr(name)} adında bir uygulama bulamadım.")
        when (target) {
            is Apps.Target.Pkg -> {
                val i = ctx.packageManager.getLaunchIntentForPackage(target.entry.pkg) ?: return Reply("${target.entry.label} açılamıyor.")
                start(i); return Reply("${target.entry.label} açılıyor.")
            }
            is Apps.Target.Sys -> {
                val i: Intent = when (target.kind) {
                    "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    "gallery" -> Intent(Intent.ACTION_VIEW).setType("image/*")
                    "settings" -> Intent(Settings.ACTION_SETTINGS)
                    "calculator" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
                    "calendar" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                    "contacts" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
                    "sms" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
                    "dialer" -> Intent(Intent.ACTION_DIAL)
                    "browser" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
                    "clock" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
                    "music" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
                    "files" -> Intent(Intent.ACTION_GET_CONTENT).setType("*/*")
                    else -> return Reply("Bunu açamadım.")
                }
                start(i); return Reply("${TextNorm.capitalizeTr(target.label)} açılıyor.")
            }
        }
    }

    // ------------------------------------------------------------------ WhatsApp

    private val waWords = setOf("whatsapp", "whatsap", "vatsap", "vatsapp", "watsap", "watsapp", "wp", "vp", "wapp", "whatsappa", "whatsapptan", "whatsappdan")
    private val lead = waWords + setOf("e", "a", "ye", "ya", "kisisine", "kisiye", "adli", "isimli", "tan", "dan", "ten", "den", "uzerinden",
        "mesaj", "mesaji", "mesajini", "olarak", "bir", "su", "sunu", "sunlari", "gir", "ac", "ve", "bak", "gonder", "yaz", "at", "de", "ona")
    private val trail = waWords + setOf("yaz", "yazar", "yazarmisin", "misin", "musun", "yazsana", "gonder", "gonderir", "gondersene", "at", "atar",
        "atsana", "de", "der", "desene", "diye", "mesaj", "mesaji", "mesajini", "olarak", "ona", "soyle", "ilet", "yolla", "lutfen", "tan", "dan", "uzerinden", "bir")

    private fun ensureContacts(): Reply? {
        if (Contacts.hasPermission(ctx)) return null
        return Reply("Kişilerine erişmem için rehber izni gerekiyor. İzin ekranını açıyorum.", ui = JSONObject().put("type", "perm").put("name", "contacts"))
    }

    private suspend fun whatsappFromSentence(raw: String): Reply {
        ensureContacts()?.let { return it }
        val orig = raw.split(Regex("[\\s'’]+")).filter { it.isNotBlank() }
        val norm = orig.map { TextNorm.basic(it) }
        val skip = waWords + setOf("mesaj", "mesaji", "yaz", "gonder", "at", "merhaba", "selam", "naber", "nasilsin", "gir", "ac", "kisisine")
        val hit = Contacts.findIn(ctx, norm, skip)
        if (hit == null) {
            pending = { name -> whatsappFromSentence("whatsapp $name " + extractMessage(orig, norm, -1, -1)) }
            return Reply("Kime yazayım?", listenAgain = true)
        }
        val msg = extractMessage(orig, norm, hit.start, hit.end)
        return whatsappSend(hit.contact, msg)
    }

    private fun extractMessage(orig: List<String>, norm: List<String>, s: Int, e: Int): String {
        fun clean(o: List<String>, n: List<String>): String {
            var a = 0; var b = n.size
            val diye = n.indexOf("diye")
            if (diye in 0 until b) b = diye
            while (a < b && n[a] in lead) a++
            while (b > a && n[b - 1] in trail) b--
            return o.subList(a, b).joinToString(" ").trim(' ', ',', '.')
        }
        if (s < 0) return clean(orig, norm).let { m -> if (norm.any { it in waWords }) "" else m }
        val after = clean(orig.subList(e, orig.size), norm.subList(e, norm.size))
        if (after.isNotBlank()) return after
        val before = clean(orig.subList(0, s), norm.subList(0, s))
        return before
    }

    private suspend fun whatsappTo(contactName: String, text: String?): Reply {
        ensureContacts()?.let { return it }
        val toks = contactName.split(Regex("[\\s'’]+")).map { TextNorm.basic(it) }.filter { it.isNotBlank() }
        val hit = Contacts.findIn(ctx, toks) ?: return Reply("Rehberinde $contactName adında birini bulamadım.")
        return whatsappSend(hit.contact, text ?: "")
    }

    private fun whatsappSend(c: Contact, msgRaw: String): Reply {
        val msg = msgRaw.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(tr) else it.toString() }
        if (msg.isBlank()) {
            pending = { t -> whatsappSend(c, t) }
            return Reply("${c.name} kişisine ne yazayım?", listenAgain = true)
        }
        val pkg = Apps.whatsappPackage(ctx) ?: return Reply("Telefonunda WhatsApp yüklü değil.")
        val num = Contacts.toInternational(c.number)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$num&text=" + Net.enc(msg))).setPackage(pkg)
        start(i)
        return Reply("${c.name} kişisine mesajı hazırladım, Gönder'e basman yeterli.")
    }

    // ------------------------------------------------------------------ arama / SMS

    private fun numberIn(raw: String): String? = Regex("[+0-9][0-9 ]{6,}").find(raw)?.value?.replace(" ", "")

    private fun call(raw: String): Reply {
        val num = numberIn(raw)
        val (label, number) = if (num != null) num to num else {
            ensureContacts()?.let { return it }
            val norm = raw.split(Regex("[\\s'’]+")).map { TextNorm.basic(it) }.filter { it.isNotBlank() }
            val hit = Contacts.findIn(ctx, norm, setOf("ara", "arar", "misin", "telefonla", "beni", "bir", "hemen"))
                ?: run {
                    pending = { name -> call("$name ara") }
                    return Reply("Kimi arayayım?", listenAgain = true)
                }
            hit.contact.name to hit.contact.number
        }
        val can = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        start(Intent(if (can) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        return Reply(if (can) "$label aranıyor." else "$label numarasını açtım, aramak için yeşil tuşa bas.")
    }

    private fun sms(raw: String): Reply {
        ensureContacts()?.let { return it }
        val orig = raw.split(Regex("[\\s'’]+")).filter { it.isNotBlank() }
        val norm = orig.map { TextNorm.basic(it) }
        val hit = Contacts.findIn(ctx, norm, setOf("sms", "mesaj", "mesaji", "yaz", "gonder", "at")) ?: return Reply("Kime mesaj atacağımı anlayamadım.")
        val msg = extractMessage(orig, norm, hit.start, hit.end).replace(Regex("(?i)\\bsms\\b"), "").trim()
        start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(hit.contact.number))).putExtra("sms_body", msg))
        return Reply("${hit.contact.name} için mesajı hazırladım.")
    }

    // ------------------------------------------------------------------ cihaz

    private fun torch(on: Boolean): Reply {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return Reply("Telefonunda fener yok.")
        cm.setTorchMode(id, on)
        return Reply(if (on) "Fener açık." else "Fener kapalı.")
    }

    private fun volume(op: String, level: Int?, text: String): Reply {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        when (op) {
            "up" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "down" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "max" -> am.setStreamVolume(s, max, AudioManager.FLAG_SHOW_UI)
            "set" -> {
                val v = level ?: 50
                val pct = TextNorm.basic(text).contains("yuzde") || v > max
                am.setStreamVolume(s, (if (pct) v * max / 100 else v).coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
            }
        }
        val now = am.getStreamVolume(s) * 100 / maxOf(1, max)
        return Reply(when (op) { "mute" -> "Ses kapatıldı."; else -> "Ses yüzde $now." })
    }

    private fun media(op: String): Reply {
        val am = ctx.getSystemService(AudioManager::class.java)
        val code = when (op) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return Reply(when (op) { "play" -> "Müzik çalıyor."; "pause" -> "Durdurdum."; "next" -> "Sonraki parça."; else -> "Önceki parça." })
    }

    private fun youtube(q: String): Reply {
        val i = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
        if (!tryStart(i)) start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Net.enc(q))))
        return Reply("YouTube'da $q açıyorum.")
    }

    private fun battery(): Reply {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return Reply("Şarj yüzde $p" + if (charging) ", şu an şarj oluyor." else ".")
    }

    private fun global(op: String): Reply = Reply("Bu işlemi bu sürümde yapamıyorum.")

    private fun settingsPanel(w: String): Reply {
        val i = when (w) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            else -> return Reply("Ayarlarımı açıyorum.", ui = JSONObject().put("type", "open_settings"))
        }
        start(i)
        return Reply(when (w) { "wifi" -> "Wi-Fi ayarlarını açtım."; "bluetooth" -> "Bluetooth ayarlarını açtım."; else -> "Ekran ayarlarını açtım." })
    }

    private fun durationText(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return listOfNotNull(if (h > 0) "$h saat" else null, if (m > 0) "$m dakika" else null, if (s > 0) "$s saniye" else null).joinToString(" ")
    }

    // ------------------------------------------------------------------ yapay zekâ

    private suspend fun chat(text: String): Reply {
        return try {
            val r = AiClient.ask(text)
            val action = r.action
            if (action != null) {
                val cmd = fromAi(action)
                if (cmd != null && cmd !is Cmd.Chat) {
                    val res = exec(cmd, text)
                    val say = listOf(r.say, res.say).filter { it.isNotBlank() }.distinct().joinToString(" ")
                    return res.copy(say = say)
                }
            }
            Reply(r.say.ifBlank { "Hmm, buna ne diyeceğimi bilemedim." })
        } catch (e: AiClient.NoKeyException) {
            Reply("Bunu tam anlayamadım. Benimle her konuda sohbet edebilmem için ayarlar, Yapay Zekâ bölümüne ücretsiz bir Gemini anahtarı ekleyebilirsin.")
        } catch (e: java.io.IOException) {
            Reply("İnternete bağlanamadım.")
        }
    }

    private fun fromAi(a: JSONObject): Cmd? = when (a.optString("type")) {
        "open_app" -> Cmd.OpenApp(a.optString("name"))
        "whatsapp" -> Cmd.WhatsAppTo(a.optString("contact"), a.optString("text").takeIf { it.isNotBlank() })
        "call" -> Cmd.Call(a.optString("contact") + " ara")
        "alarm" -> Cmd.Alarm(a.optInt("hour"), a.optInt("minute"), null)
        "timer" -> Cmd.Timer(a.optInt("seconds", 60))
        "youtube" -> Cmd.YouTube(a.optString("query"))
        "search" -> Cmd.Search(a.optString("query"))
        "navigate" -> Cmd.Navigate(a.optString("dest"))
        "weather" -> Cmd.Weather(a.optString("city").takeIf { it.isNotBlank() }, a.optBoolean("tomorrow"))
        "tv" -> Cmd.Tv(a.optString("op"), if (a.has("value") && !a.isNull("value")) a.optInt("value") else null)
        "volume" -> Cmd.Volume(a.optString("op"), if (a.has("level") && !a.isNull("level")) a.optInt("level") else null)
        "torch" -> Cmd.Torch(a.optBoolean("on", true))
        "media" -> Cmd.Media(a.optString("op"))
        "note" -> Cmd.Note(a.optString("text"))
        else -> null
    }

    companion object {
        fun greeting(): String {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val g = when (h) { in 5..11 -> "Günaydın"; in 12..17 -> "İyi günler"; in 18..22 -> "İyi akşamlar"; else -> "İyi geceler" }
            return "$g ${Prefs.str("userName").ifBlank { "efendim" }}. Koser hazır."
        }
    }
}
