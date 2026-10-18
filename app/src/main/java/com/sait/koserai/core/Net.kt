package com.sait.koserai.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            JSONObject(r.body!!.string())
        }
    }
}

/** Open-Meteo: anahtar gerektirmez. */
object Weather {
    private val CODES = mapOf(
        0 to "açık", 1 to "az bulutlu", 2 to "parçalı bulutlu", 3 to "kapalı", 45 to "sisli", 48 to "kırağılı sis",
        51 to "hafif çiseleyen", 53 to "çiseleyen", 55 to "yoğun çiseleyen", 61 to "hafif yağmurlu", 63 to "yağmurlu", 65 to "şiddetli yağmurlu",
        66 to "dondurucu yağmurlu", 67 to "dondurucu yağmurlu", 71 to "hafif karlı", 73 to "karlı", 75 to "yoğun karlı", 77 to "kar taneli",
        80 to "sağanak yağışlı", 81 to "sağanak yağışlı", 82 to "şiddetli sağanaklı", 85 to "kar sağanaklı", 86 to "yoğun kar sağanaklı",
        95 to "gök gürültülü fırtınalı", 96 to "dolu ve fırtınalı", 99 to "dolu ve fırtınalı"
    )

    /** Ana ekran kartı için: anlık sıcaklık ve durum */
    suspend fun current(city: String): JSONObject {
        val geo = Net.getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&language=tr&name=${Net.enc(city)}")
        val res = geo.optJSONArray("results")?.optJSONObject(0) ?: throw RuntimeException("Şehir bulunamadı: $city")
        val w = Net.getJson("https://api.open-meteo.com/v1/forecast?latitude=${res.getDouble("latitude")}&longitude=${res.getDouble("longitude")}" +
            "&timezone=auto&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1")
        val c = w.getJSONObject("current")
        val code = c.getInt("weather_code")
        val d = w.getJSONObject("daily")
        return JSONObject().put("city", res.optString("name", city)).put("temp", Math.round(c.getDouble("temperature_2m")))
            .put("code", code).put("isDay", c.optInt("is_day", 1) == 1).put("desc", (CODES[code] ?: "değişken").replaceFirstChar { it.titlecase(java.util.Locale("tr")) })
            .put("max", Math.round(d.getJSONArray("temperature_2m_max").getDouble(0))).put("min", Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)))
    }

    suspend fun describe(city: String, tomorrow: Boolean): String {
        val geo = Net.getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&language=tr&name=${Net.enc(city)}")
        val res = geo.optJSONArray("results")?.optJSONObject(0) ?: return "$city diye bir yer bulamadım."
        val lat = res.getDouble("latitude"); val lon = res.getDouble("longitude")
        val name = res.optString("name", city)
        val w = Net.getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=2"
        )
        val d = w.getJSONObject("daily")
        return if (tomorrow) {
            val code = d.getJSONArray("weather_code").getInt(1)
            val mx = d.getJSONArray("temperature_2m_max").getDouble(1).toInt()
            val mn = d.getJSONArray("temperature_2m_min").getDouble(1).toInt()
            val p = d.getJSONArray("precipitation_probability_max").optInt(1, 0)
            "Yarın $name ${CODES[code] ?: "değişken"} olacak. En yüksek $mx, en düşük $mn derece. Yağış ihtimali yüzde $p."
        } else {
            val c = w.getJSONObject("current")
            val temp = c.getDouble("temperature_2m").toInt()
            val feel = c.getDouble("apparent_temperature").toInt()
            val code = c.getInt("weather_code")
            val mx = d.getJSONArray("temperature_2m_max").getDouble(0).toInt()
            val mn = d.getJSONArray("temperature_2m_min").getDouble(0).toInt()
            "$name şu an $temp derece ve ${CODES[code] ?: "değişken"}. Hissedilen $feel. Bugün en yüksek $mx, en düşük $mn derece."
        }
    }
}

/** Yapay zekâ beyni: Google Gemini (ücretsiz anahtar: aistudio.google.com). */
object AiClient {
    private val history = ArrayList<Pair<String, String>>() // role, text

    fun reset() = history.clear()

    private fun systemPrompt(): String {
        val user = Prefs.str("userName").ifBlank { "efendim" }
        return """
Sen ${com.sait.koserai.data.Persona.active().name}'sın: KoserAI uygulamasında yaşayan, Sait tarafından geliştirilmiş, Türkçe konuşan kişisel yapay zekâ asistanı. Kişiliğin: ${com.sait.koserai.data.Persona.active().style}
Kullanıcıya "$user" diye hitap et. Cevapların sesli okunacak: kısa ve doğal konuş (genelde 1-3 cümle), madde işareti, emoji, markdown kullanma.
Seni kim yaptı diye sorulursa: "Beni Sait geliştirdi." de.

Telefonda bir işlem yapılması gerekiyorsa "action" alanını doldur, yoksa null bırak. Kullanılabilecek işlemler:
- {"type":"open_app","name":"uygulama adı"}
- {"type":"whatsapp","contact":"kişi adı","text":"mesaj"}
- {"type":"call","contact":"kişi adı veya numara"}
- {"type":"alarm","hour":7,"minute":30}
- {"type":"timer","seconds":300}
- {"type":"youtube","query":"..."}
- {"type":"search","query":"..."}
- {"type":"navigate","dest":"..."}
- {"type":"weather","city":"..." , "tomorrow":false}
- {"type":"tv","op":"on|off|vol_up|vol_down|mute|ch_up|ch_down|set_vol|home|source","value":null}
- {"type":"volume","op":"up|down|mute|unmute|max|set","level":null}
- {"type":"torch","on":true}
- {"type":"media","op":"play|pause|next|prev"}
- {"type":"note","text":"..."}
Her zaman şu JSON biçiminde cevap ver: {"say":"söyleyeceğin cümle","action":null}
        """.trimIndent()
    }

    data class AiReply(val say: String, val action: JSONObject?)

    suspend fun ask(text: String): AiReply = withContext(Dispatchers.IO) {
        val key = Prefs.str("geminiKey").trim()
        if (key.isBlank()) throw NoKeyException()
        val model = Prefs.str("geminiModel").ifBlank { "gemini-2.5-flash" }
        history.add("user" to text)
        while (history.size > 16) history.removeAt(0)

        val contents = JSONArray()
        for ((role, t) in history) {
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", t))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.7).put("maxOutputTokens", 2048))

        val tried = (listOf(model) + listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.5-flash-lite", "gemini-flash-lite-latest")).distinct()
        var lastErr = ""
        for (m in tried) {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/${m}:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val result: AiReply? = Net.http.newCall(req).execute().use { r ->
                val txt = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    lastErr = try { JSONObject(txt).getJSONObject("error").optString("message") } catch (e: Exception) { txt.take(120) }
                    if (r.code == 400 && lastErr.contains("API key", true)) { history.removeAt(history.size - 1); throw RuntimeException("Gemini anahtarı geçersiz görünüyor.") }
                    null // 429 kota / 404 model: sıradaki modeli dene
                } else {
                    val out = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    history.add("model" to out)
                    try {
                        val j = JSONObject(out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
                        AiReply(j.optString("say", ""), j.optJSONObject("action"))
                    } catch (e: Exception) { AiReply(out.trim(), null) }
                }
            }
            if (result != null) { if (m != model) Prefs.put("geminiModel", m); return@withContext result }
        }
        history.removeAt(history.size - 1)
        throw RuntimeException("Gemini şu an cevap vermiyor (kota dolmuş olabilir). " + lastErr.take(120))
    }

    class NoKeyException : Exception("no key")
}

/** "12 çarpı 4", "100 bölü 8", "5+3*2" */
object Calc {
    fun eval(input: String): Double? {
        var s = input.lowercase(java.util.Locale("tr"))
            .replace('ı', 'i').replace('ç', 'c').replace('ş', 's').replace('ö', 'o').replace('ü', 'u').replace('ğ', 'g')
        s = s.replace("arti", "+").replace("eksi", "-").replace("carpi", "*").replace("kere", "*")
            .replace("bolu", "/").replace("×", "*").replace("÷", "/").replace(",", ".")
        s = Regex("(\\d)\\s*x\\s*(\\d)").replace(s) { "${it.groupValues[1]}*${it.groupValues[2]}" }
        val expr = Regex("[0-9.+\\-*/()]+").findAll(s).joinToString("") { it.value }
        if (expr.isBlank() || expr.none { it in "+-*/" }) return null
        return try { Parser(expr).parse() } catch (e: Exception) { null }
    }

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); if (i != s.length) throw IllegalStateException(); return v }
        fun expr(): Double { var v = term(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }; return v }
        fun term(): Double { var v = factor(); while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = factor(); v = if (op == '*') v * r else v / r }; return v }
        fun factor(): Double {
            if (s[i] == '-') { i++; return -factor() }
            if (s[i] == '(') { i++; val v = expr(); i++; return v }
            val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
    }

    fun say(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e12) v.toLong().toString() else "%.2f".format(v).replace('.', ',')
}
