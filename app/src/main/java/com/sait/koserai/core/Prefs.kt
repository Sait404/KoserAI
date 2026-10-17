package com.sait.koserai.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Tüm ayarlar tek bir JSON nesnesinde tutulur; web arayüzü ile doğrudan paylaşılır. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private var cache: JSONObject? = null

    const val WAKE_REQUIRED_WORD = "koser"

    private val DEFAULTS = JSONObject().apply {
        put("onboarded", false)
        put("userName", "efendim")
        put("wakeEnabled", false)
        put("wakePhrase", "hey koser")
        put("wakeAliases", JSONArray())
        put("wakeSensitivity", 2)
        put("theme", "koser")
        put("ttsRate", 1.0)
        put("ttsPitch", 1.0)
        put("sounds", true)
        put("aiProvider", "gemini")
        put("geminiKey", "")
        put("geminiModel", "gemini-2.5-flash")
        put("city", "İstanbul")
        put("autoSend", true)
        put("tvType", "none")
        put("tvName", "Televizyon")
        put("tvIp", "")
        put("tvMac", "")
        put("tvSecure", true)
        put("tvToken", "")
        put("lgClientKey", "")
        put("httpHeader", "")
        put("httpPowerOn", "")
        put("httpPowerOff", "")
        put("httpVolUp", "")
        put("httpVolDown", "")
        put("httpMute", "")
        put("httpChUp", "")
        put("httpChDown", "")
        put("notes", JSONArray())
    }

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("koser", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun all(): JSONObject {
        cache?.let { return it }
        val stored = try { JSONObject(sp.getString("settings", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val merged = JSONObject(DEFAULTS.toString())
        stored.keys().forEach { k -> merged.put(k, stored.get(k)) }
        cache = merged
        return merged
    }

    @Synchronized
    fun save(obj: JSONObject) {
        cache = obj
        sp.edit().putString("settings", obj.toString()).apply()
    }

    /** Web arayüzünden gelen kısmi güncellemeyi uygular. Uyandırma cümlesi kuralını burada da zorlar. */
    @Synchronized
    fun merge(patch: JSONObject): String? {
        val cur = all()
        if (patch.has("wakePhrase")) {
            val p = normalizePhrase(patch.getString("wakePhrase"))
            if (!isValidWakePhrase(p)) return "Uyandırma cümlesinde mutlaka \"Koser\" kelimesi olmalı."
            if (p != cur.optString("wakePhrase")) cur.put("wakeAliases", JSONArray()) // yeni cümle, eski öğrenilenler geçersiz
            patch.put("wakePhrase", p)
        }
        patch.keys().forEach { k -> cur.put(k, patch.get(k)) }
        save(cur)
        return null
    }

    fun normalizePhrase(s: String) = s.trim().lowercase(java.util.Locale("tr")).replace(Regex("\\s+"), " ")

    fun isValidWakePhrase(p: String): Boolean {
        val words = TextNorm.basic(p).split(" ").filter { it.isNotBlank() }
        return words.contains(WAKE_REQUIRED_WORD) && words.size in 1..4 && p.length <= 40
    }

    fun str(k: String) = all().optString(k, "")
    fun bool(k: String) = all().optBoolean(k, false)
    fun dbl(k: String) = all().optDouble(k, 1.0)
    fun int(k: String) = all().optInt(k, 0)
    fun put(k: String, v: Any) { val o = all(); o.put(k, v); save(o) }

    fun getPin(host: String): String? = sp.getString("pin_$host", null)
    fun setPin(host: String, fp: String) = sp.edit().putString("pin_$host", fp).apply()
    fun clearPins() {
        val e = sp.edit(); sp.all.keys.filter { it.startsWith("pin_") }.forEach { e.remove(it) }; e.apply()
    }
}
