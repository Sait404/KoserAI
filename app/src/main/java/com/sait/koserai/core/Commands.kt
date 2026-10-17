package com.sait.koserai.core

/** Koser'in anlayabildiği komutlar */
sealed class Cmd {
    data class OpenApp(val name: String) : Cmd()
    data class WhatsApp(val raw: String) : Cmd()               // kişi + mesaj cümleden çözülür
    data class WhatsAppTo(val contact: String, val text: String?) : Cmd()   // yapay zekâdan gelen hazır hali
    data class Call(val raw: String) : Cmd()
    data class Sms(val raw: String) : Cmd()
    data class Alarm(val hour: Int, val minute: Int, val label: String?) : Cmd()
    data class Timer(val seconds: Int) : Cmd()
    data class Torch(val on: Boolean) : Cmd()
    data class Volume(val op: String, val level: Int? = null) : Cmd()   // up/down/mute/unmute/max/set
    data class Media(val op: String) : Cmd()                            // play/pause/next/prev
    data class YouTube(val query: String) : Cmd()
    data class Search(val query: String) : Cmd()
    data class Navigate(val dest: String) : Cmd()
    object Time : Cmd()
    object Date : Cmd()
    object Battery : Cmd()
    data class Weather(val city: String?, val tomorrow: Boolean) : Cmd()
    data class Tv(val op: String, val value: Int? = null) : Cmd()      // on/off/vol_up/vol_down/mute/ch_up/ch_down/set_vol/home/source
    data class Global(val op: String) : Cmd()                           // screenshot/home/back/recents/lock/notifications
    data class Theme(val id: String) : Cmd()
    data class Note(val text: String) : Cmd()
    object ReadNotes : Cmd()
    object ClearNotes : Cmd()
    object Stop : Cmd()
    data class SettingsPanel(val which: String) : Cmd()                 // wifi/bluetooth/display/sound/koser
    data class Camera(val selfie: Boolean) : Cmd()
    data class Calc(val expr: String) : Cmd()
    object Coin : Cmd()
    object Dice : Cmd()
    object WhoAreYou : Cmd()
    data class Chat(val text: String) : Cmd()
}

object CommandParser {

    private val WA = setOf("whatsapp", "whatsap", "vatsap", "vatsapp", "watsap", "watsapp", "wp", "vp", "wapp", "whatsappa", "whatsapptan")
    private val TV = setOf("televizyon", "televizyonu", "televizyonun", "televizyona", "televizyonda", "tv", "tvyi", "tvnin", "tele", "teve", "tivi")
    private val OPEN = setOf("ac", "gir", "baslat", "calistir", "acsana", "acar", "girer", "ac", "aciver", "acabilir")
    private val CLOSE = setOf("kapat", "kapa", "kapatsana", "kapatir", "kapatabilir", "sondur")

    fun parse(input: String): Cmd {
        val t = TextNorm.basic(input)
        val w = t.split(" ").filter { it.isNotBlank() }
        val has = { s: String -> w.contains(s) }
        val hasAny = { set: Collection<String> -> w.any { it in set } }
        val starts = { p: String -> w.any { it.startsWith(p) } }

        if (w.isEmpty()) return Cmd.Chat(input)

        // --- durdur / sus
        if (t in setOf("sus", "dur", "tamam", "yeter", "kapat", "tamam yeter", "sessiz ol", "gorusuruz", "hosca kal", "iptal", "vazgec", "bosver"))
            return Cmd.Stop

        // --- kimlik
        if (t.contains("adin ne") || t.contains("sen kimsin") || t.contains("kimsin sen") || t.contains("seni kim yapti") ||
            t.contains("yapimcin kim") || t.contains("seni kim yazdi") || t.contains("kim tarafindan"))
            return Cmd.WhoAreYou

        // --- tema
        if (starts("tema") || starts("mod") || t.contains("gorunum")) {
            when {
                t.contains("kizil") || t.contains("ultron") || t.contains("kirmizi") -> return Cmd.Theme("kizil")
                t.contains("altin") || t.contains("sari") || t.contains("gold") -> return Cmd.Theme("altin")
                t.contains("normal") || t.contains("mavi") || t.contains("koser") || t.contains("varsayilan") -> return Cmd.Theme("koser")
            }
        }

        // --- TV
        if (hasAny(TV) || t.contains("televizyon")) return parseTv(t, w)

        // --- WhatsApp
        if (hasAny(WA) || starts("whatsapp") || starts("vatsap") || starts("watsap")) {
            val msgVerb = w.any { it in setOf("yaz", "yazar", "yazsana", "yazarmisin", "gonder", "yolla", "at", "ilet", "de", "soyle", "mesaj", "mesaji", "diye") }
            return if (msgVerb) Cmd.WhatsApp(input) else Cmd.OpenApp("whatsapp")
        }

        // --- fener
        if (starts("fener") || starts("flas") || t.contains("el feneri") || (starts("isig") && !t.contains("ekran"))) {
            return Cmd.Torch(!(hasAny(CLOSE) || has("sondur")))
        }

        // --- ekran / sistem
        if (t.contains("ekran goruntusu") || t == "ss al" || t.contains("ekran resmi")) return Cmd.Global("screenshot")
        if (t.contains("kilitle")) return Cmd.Global("lock")
        if (t.contains("ana ekran") || t == "ana sayfa" || t.contains("ana ekrana")) return Cmd.Global("home")
        if (t.contains("bildirim") && (has("ac") || has("goster"))) return Cmd.Global("notifications")
        if (t.contains("son uygulamalar")) return Cmd.Global("recents")

        // --- not
        if (t.startsWith("not al") || t.startsWith("not et") || t.startsWith("not yaz") || t.startsWith("hatirla ")) {
            val txt = input.trim().split(Regex("\\s+")).drop(2).joinToString(" ")
            return Cmd.Note(txt)
        }
        if (t.contains("notlarimi") || t.contains("notlari oku") || t.contains("notlarim")) {
            return if (t.contains("sil") || t.contains("temizle")) Cmd.ClearNotes else Cmd.ReadNotes
        }

        // --- saat / tarih / pil
        if (t.contains("saat kac") || t == "saat" || t.contains("saati soyle") || t.contains("saat ne")) return Cmd.Time
        if (t.contains("bugun gunlerden") || t.contains("tarih") || t.contains("ayin kaci") || t.contains("bugun ne gunu") || t.contains("hangi gun")) return Cmd.Date
        if (starts("sarj") || starts("batarya") || (starts("pil") && !starts("pilav"))) return Cmd.Battery

        // --- hava
        if (has("hava") || starts("havad") || t.contains("yagmur yag") || t.contains("kar yag") || t.contains("sicaklik") || t.contains("derece kac")) {
            return Cmd.Weather(extractCity(w), t.contains("yarin"))
        }

        // --- alarm / zamanlayıcı
        if (starts("alarm") || t.contains("beni uyandir") || t.contains("uyandir")) {
            parseClock(w)?.let { (h, m) -> return Cmd.Alarm(h, m, null) }
            return Cmd.OpenApp("alarm")
        }
        if (starts("zamanlayici") || starts("sayac") || t.contains("geri sayim") || starts("timer") || (t.contains("sonra") && t.contains("haber ver"))) {
            parseDuration(w)?.let { return Cmd.Timer(it) }
        }

        // --- ses (telefon)
        if (starts("ses") || starts("sessiz") || starts("volume")) {
            val num = numbers(w).firstOrNull()
            return when {
                t.contains("sessize") || t.contains("sesi kapat") || t.contains("sesini kapat") -> Cmd.Volume("mute")
                t.contains("sonuna") || t.contains("full") || t.contains("en yuksek") || t.contains("maksimum") -> Cmd.Volume("max")
                num != null && (has("yap") || has("ayarla") || starts("yuzde")) -> Cmd.Volume("set", num)
                w.any { it.startsWith("kis") || it.startsWith("azalt") || it.startsWith("dusur") || it == "alcalt" } -> Cmd.Volume("down")
                w.any { it.startsWith("ac") || it.startsWith("arttir") || it.startsWith("artir") || it.startsWith("yukselt") || it.startsWith("yukari") } ->
                    if (t.contains("sessiz")) Cmd.Volume("unmute") else Cmd.Volume("up")
                else -> Cmd.Volume("up")
            }
        }

        // --- medya
        if (t.contains("sonraki sarki") || t.contains("siradaki") || t.contains("sarkiyi gec") || t == "gec" || t.contains("sonraki parca")) return Cmd.Media("next")
        if (t.contains("onceki sarki") || t.contains("onceki parca") || t.contains("basa sar")) return Cmd.Media("prev")
        if ((starts("muzig") || starts("sarki") || starts("video")) && (hasAny(setOf("durdur", "duraklat", "kapat", "dur")))) return Cmd.Media("pause")
        if (t == "durdur" || t == "duraklat") return Cmd.Media("pause")
        if (t == "devam et" || t == "devam" || ((starts("muzig") || starts("muzik")) && (has("cal") || has("baslat") || has("ac") || has("devam")))) return Cmd.Media("play")

        // --- YouTube
        if (starts("youtube") || starts("yutup") || starts("yutub")) {
            val q = stripWords(input, setOf("youtube", "youtubeda", "youtubede", "yutup", "yutub", "da", "de", "ta", "dan", "u", "ac", "ara", "cal", "acar", "misin", "bul", "bana", "goster", "uzerinden", "ten", "tan"))
            return if (q.isBlank()) Cmd.OpenApp("youtube") else Cmd.YouTube(q)
        }
        if (w.last() == "cal" || w.last() == "calsana" || (w.size >= 2 && w[w.size - 2] == "cal")) {
            val q = stripWords(input, setOf("cal", "calsana", "bana", "bir", "misin", "biraz", "sarki", "sarkisi", "sarkisini"))
            if (q.isNotBlank()) return Cmd.YouTube(q)
        }

        // --- yol tarifi
        if (t.contains("yol tarifi") || t.contains("nasil giderim") || starts("navigasyon") || t.contains("haritada") || t.contains("konumunu goster")) {
            val q = stripWords(input, setOf("yol", "tarifi", "tarif", "ver", "nasil", "giderim", "navigasyon", "haritada", "goster", "konumunu", "a", "e", "ya", "ye", "baslat", "icin"))
            if (q.isNotBlank()) return Cmd.Navigate(q)
        }

        // --- arama motoru
        if (starts("google") || starts("internet") || t.contains("arastir")) {
            val q = stripWords(input, setOf("google", "googleda", "googlede", "da", "de", "internette", "internetten", "ara", "arastir", "arat", "bul", "bana", "hakkinda", "ile", "ilgili", "bilgi"))
            if (q.isNotBlank()) return Cmd.Search(q)
        }

        // --- kamera
        if (starts("selfie") || t.contains("on kamera")) return Cmd.Camera(true)
        if (starts("fotograf cek") || t.contains("foto cek") || t.contains("fotograf cek") || t.startsWith("kamera")) return Cmd.Camera(false)

        // --- ayar panelleri
        if (starts("wifi") || starts("wi fi") || starts("kablosuz") || t.contains("vay fay")) return Cmd.SettingsPanel("wifi")
        if (starts("bluetooth") || starts("blutut") || starts("bluetut")) return Cmd.SettingsPanel("bluetooth")
        if (starts("parlaklik")) return Cmd.SettingsPanel("display")
        if ((starts("koser") || t.contains("senin ayar") || t == "ayarlarini ac") && starts("ayar")) return Cmd.SettingsPanel("koser")

        // --- eğlence
        if (t.contains("yazi tura")) return Cmd.Coin
        if (t.contains("zar at")) return Cmd.Dice

        // --- hesap
        if (Regex("\\d").containsMatchIn(t) && (w.any { it in setOf("arti", "eksi", "carpi", "bolu", "kere", "kac", "eder", "topla", "cikar", "karekok") } ||
                    Regex("\\d\\s*[+\\-x*/]\\s*\\d").containsMatchIn(input))) {
            return Cmd.Calc(input)
        }

        // --- telefon araması: "Ahmet'i ara", "annemi ara"
        if (w.last() in setOf("ara", "arasana", "arar", "aramasi", "cevir") || t.contains("ara beni") || (w.size >= 2 && w[w.size - 2] == "ara" && w.last() in setOf("misin", "musun"))) {
            if (!t.contains("youtube") && !t.contains("google")) return Cmd.Call(input)
        }
        // --- SMS
        if (starts("sms") || (t.contains("mesaj") && (has("gonder") || has("at") || has("yaz") || has("yolla")))) return Cmd.Sms(input)

        // --- uygulama aç
        if (w.last() in OPEN || (w.size >= 2 && w[w.size - 2] in OPEN && w.last() in setOf("misin", "musun", "bakalim", "lutfen"))) {
            val idx = w.indexOfLast { it in OPEN }
            val name = w.subList(0, idx).filter { it !in setOf("bana", "bir", "su", "hemen", "lutfen", "uygulamasini", "uygulamayi", "uygulama", "i", "u", "yi", "yu", "a", "e", "ya", "ye") }
            if (name.isNotEmpty()) return Cmd.OpenApp(name.joinToString(" "))
        }

        return Cmd.Chat(input)
    }

    private fun parseTv(t: String, w: List<String>): Cmd {
        val n = numbers(w).firstOrNull()
        val isVol = w.any { it.startsWith("ses") }
        val isCh = w.any { it.startsWith("kanal") }
        return when {
            t.contains("sessiz") || (isVol && w.any { it in CLOSE }) -> Cmd.Tv("mute")
            isVol && n != null && (t.contains("yap") || t.contains("ayarla") || t.contains("seviye")) -> Cmd.Tv("set_vol", n)
            isVol && w.any { it.startsWith("kis") || it.startsWith("azalt") || it.startsWith("dusur") } -> Cmd.Tv("vol_down", n)
            isVol -> Cmd.Tv("vol_up", n)
            isCh && w.any { it.startsWith("geri") || it.startsWith("onceki") || it.startsWith("asagi") } -> Cmd.Tv("ch_down")
            isCh -> Cmd.Tv("ch_up")
            t.contains("ana ekran") || t.contains("ana sayfa") -> Cmd.Tv("home")
            t.contains("kaynak") || t.contains("giris") || t.contains("hdmi") -> Cmd.Tv("source")
            w.any { it in CLOSE } -> Cmd.Tv("off")
            w.any { it in OPEN || it == "calistir" } -> Cmd.Tv("on")
            else -> Cmd.Tv("on")
        }
    }

    private fun extractCity(w: List<String>): String? {
        val stop = setOf("hava", "havalar", "durumu", "nasil", "bugun", "yarin", "simdi", "su", "an", "da", "de", "ta", "te", "kac", "derece",
            "yagmur", "kar", "yagacak", "mi", "mu", "sicaklik", "sicak", "soguk", "ne", "olacak", "bana", "soyle", "orada", "burada", "havasi")
        val idx = w.indexOfFirst { it in setOf("da", "de", "ta", "te") }
        if (idx > 0 && w[idx - 1] !in stop) return TextNorm.capitalizeTr(w[idx - 1])
        val cand = w.firstOrNull { it !in stop && it.length > 2 && !it.all { c -> c.isDigit() } }
        return cand?.let { c ->
            val base = listOf("nda", "nde", "da", "de", "ta", "te").firstOrNull { c.endsWith(it) && c.length > it.length + 2 }?.let { c.dropLast(it.length) } ?: c
            if (base in stop) null else TextNorm.capitalizeTr(base)
        }
    }

    private val NUM_WORDS = mapOf(
        "sifir" to 0, "bir" to 1, "iki" to 2, "uc" to 3, "dort" to 4, "bes" to 5, "alti" to 6, "yedi" to 7, "sekiz" to 8, "dokuz" to 9,
        "on" to 10, "yirmi" to 20, "otuz" to 30, "kirk" to 40, "elli" to 50, "altmis" to 60, "yetmis" to 70, "seksen" to 80, "doksan" to 90, "yuz" to 100
    )

    /** Cümledeki sayılar: "yirmi beş" -> 25, "7:30" -> 7, 30 */
    fun numbers(w: List<String>): List<Int> {
        val out = ArrayList<Int>()
        var acc = -1
        for (tok in w) {
            val d = tok.filter { it.isDigit() }
            if (d.isNotEmpty() && d.length <= 4 && tok.takeWhile { it.isDigit() }.isNotEmpty()) {
                if (acc >= 0) { out.add(acc); acc = -1 }
                out.add(d.toInt()); continue
            }
            val base = NUM_WORDS[tok] ?: NUM_WORDS[TextNorm.stripSuffix(tok)]
            if (base != null) {
                acc = if (acc < 0) base else if (base < 10 && acc % 10 == 0) acc + base else { out.add(acc); base }
            } else if (acc >= 0) { out.add(acc); acc = -1 }
        }
        if (acc >= 0) out.add(acc)
        return out
    }

    fun parseClock(w: List<String>): Pair<Int, Int>? {
        val joined = w.joinToString(" ")
        val nums = numbers(w)
        if (nums.isEmpty()) return null
        var h = nums[0]
        var m = if (nums.size > 1 && nums[1] < 60) nums[1] else 0
        if (joined.contains("bucuk") || joined.contains("yarim")) m = 30
        if (h > 24 && h in 100..2400) { m = h % 100; h /= 100 }
        if ((joined.contains("aksam") || joined.contains("gece") || joined.contains("ogleden sonra")) && h in 1..11) h += 12
        if (joined.contains("gece") && h == 24) h = 0
        if (h !in 0..23) return null
        return h to m
    }

    fun parseDuration(w: List<String>): Int? {
        var total = 0
        var last: Int? = null
        val joined = w.joinToString(" ")
        for (tok in w) {
            val n = numbers(listOf(tok)).firstOrNull()
            if (n != null) { last = (if (last != null && n < 10 && last % 10 == 0) last + n else n); continue }
            when {
                tok.startsWith("saniye") -> { total += (last ?: 1); last = null }
                tok.startsWith("dakika") || tok.startsWith("dk") -> { total += (last ?: 1) * 60; last = null }
                tok.startsWith("saat") -> { total += (last ?: 1) * 3600; last = null }
            }
        }
        if (joined.contains("yarim saat")) total += 1800
        if (total == 0 && last != null) total = last * 60
        return if (total > 0) total else null
    }

    /** Orijinal cümleden komut kelimelerini at, geri kalanı (orijinal yazımla) döndür. */
    fun stripWords(input: String, drop: Set<String>): String {
        return input.split(Regex("[\\s'’]+"))
            .filter { it.isNotBlank() && TextNorm.basic(it) !in drop && TextNorm.basic(it).isNotBlank() }
            .joinToString(" ")
            .trim(' ', '.', ',', '?', '!')
    }
}
