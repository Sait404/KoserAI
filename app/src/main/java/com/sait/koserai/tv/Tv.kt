package com.sait.koserai.tv

import android.content.Context
import android.util.Base64
import com.sait.koserai.core.Net
import com.sait.koserai.core.Prefs
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TvException(msg: String) : Exception(msg)

/**
 * Televizyon kontrolü. Desteklenenler:
 *  - samsung : Tizen akıllı TV (2016+) yerel ağ uzaktan kumanda API'si
 *  - lg      : LG webOS yerel ağ API'si
 *  - roku    : Roku / Roku TV (ECP)
 *  - http    : Kendi API'n (Home Assistant, ev otomasyonu, kendi sunucun...) — her komut için URL
 * Açma için Wake-on-LAN (MAC adresi girilirse) kullanılır.
 */
object TvController {

    suspend fun run(ctx: Context, op: String, value: Int?): String = withContext(Dispatchers.IO) {
        val type = Prefs.str("tvType")
        val ip = Prefs.str("tvIp").trim()
        val name = Prefs.str("tvName").ifBlank { "Televizyon" }
        if (type == "none" || type.isBlank()) throw TvException("Henüz bir televizyon eklemedin. Ayarlar, Televizyon bölümünden ekleyebilirsin.")
        if (type != "http" && ip.isBlank()) throw TvException("Televizyonun IP adresi ayarlarda boş.")
        when (type) {
            "samsung" -> Samsung.run(ip, op, value, name)
            "lg" -> Lg.run(ctx, ip, op, value, name)
            "roku" -> Roku.run(ip, op, value, name)
            "http" -> HttpTv.run(op, value, name)
            else -> throw TvException("Bilinmeyen TV türü.")
        }
    }

    fun opText(op: String, name: String) = when (op) {
        "on" -> "$name açılıyor."
        "off" -> "$name kapatıldı."
        "vol_up" -> "$name sesi artırıldı."
        "vol_down" -> "$name sesi azaltıldı."
        "mute" -> "$name sessize alındı."
        "set_vol" -> "$name ses seviyesi ayarlandı."
        "ch_up" -> "Sonraki kanal."
        "ch_down" -> "Önceki kanal."
        "home" -> "$name ana ekrana döndü."
        "source" -> "Giriş kaynağı değiştiriliyor."
        else -> "Tamam."
    }

    // ---------- yardımcılar ----------

    fun wakeOnLan(macRaw: String, ip: String) {
        val hex = macRaw.replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 12) throw TvException("MAC adresi geçersiz. Örnek: A1:B2:C3:D4:E5:F6")
        val mac = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val pkt = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) pkt[i] = 0xFF.toByte()
        for (i in 1..16) System.arraycopy(mac, 0, pkt, i * 6, 6)
        val targets = mutableListOf("255.255.255.255")
        val parts = ip.split(".")
        if (parts.size == 4) targets.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
        DatagramSocket().use { s ->
            s.broadcast = true
            repeat(3) {
                for (t in targets) for (port in intArrayOf(9, 7)) {
                    try { s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(t), port)) } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Televizyonlar kendi imzaladıkları sertifikayı kullanır. İlk bağlantıda sertifikanın parmak izi
     * kaydedilir ve sonraki bağlantılarda yalnızca aynı sertifika kabul edilir (TOFU). Sadece bu TV adresi için.
     */
    class PinnedTrust(private val key: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("İstemci sertifikası desteklenmiyor")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Sertifika yok")
            val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
            val pinned = Prefs.getPin(key)
            if (pinned == null) Prefs.setPin(key, fp)
            else if (pinned != fp) throw CertificateException("Televizyonun güvenlik sertifikası değişti. Ayarlardan TV eşleşmesini sıfırla.")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun client(secureHost: String? = null, port: Int = 0): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
        if (secureHost != null) {
            val tm = PinnedTrust("$secureHost:$port")
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            b.sslSocketFactory(ctx.socketFactory, tm)
            b.hostnameVerifier { host, _ -> host == secureHost }
        }
        return b.build()
    }

    /** Basit, coroutine dostu WebSocket */
    class Ws(private val client: OkHttpClient, private val url: String) {
        val incoming = Channel<String>(Channel.UNLIMITED)
        private var ws: WebSocket? = null

        suspend fun open() = suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
            var resumed = false
            ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!resumed) { resumed = true; cont.resume(Unit) }
                }
                override fun onMessage(webSocket: WebSocket, text: String) { incoming.trySend(text) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!resumed) { resumed = true; cont.resumeWithException(t) } else incoming.close(t)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.close() }
            })
            cont.invokeOnCancellation { ws?.cancel() }
        }

        fun send(s: String) { ws?.send(s) }
        fun close() { ws?.close(1000, null) }
    }

    fun reachableMsg(e: Throwable, name: String): TvException =
        if (e is TvException) e
        else if (e is javax.net.ssl.SSLException || e is CertificateException) TvException(e.message ?: "$name ile güvenli bağlantı kurulamadı.")
        else TvException("$name'a ulaşamadım. Açık ve aynı Wi-Fi ağında olduğundan emin ol.")
}

// ===================================================================== Samsung

object Samsung {
    private val KEYS = mapOf(
        "off" to "KEY_POWER", "on" to "KEY_POWER", "vol_up" to "KEY_VOLUP", "vol_down" to "KEY_VOLDOWN", "mute" to "KEY_MUTE",
        "ch_up" to "KEY_CHUP", "ch_down" to "KEY_CHDOWN", "home" to "KEY_HOME", "source" to "KEY_SOURCE"
    )

    suspend fun run(ip: String, op: String, value: Int?, name: String): String {
        if (op == "on") {
            val mac = Prefs.str("tvMac")
            if (mac.isNotBlank()) { TvController.wakeOnLan(mac, ip); return TvController.opText(op, name) }
        }
        if (op == "set_vol") throw TvException("Samsung televizyonlarda sesi tam bir seviyeye ayarlayamıyorum. \"Sesi artır\" ya da \"sesi kıs\" diyebilirsin.")
        val key = KEYS[op] ?: throw TvException("Bu komutu bu televizyonda yapamıyorum.")
        val times = if (op == "vol_up" || op == "vol_down") (value ?: 3).coerceIn(1, 30) else 1

        val secure = Prefs.bool("tvSecure")
        try {
            send(ip, key, times, secure)
        } catch (e: Exception) {
            if (secure && e !is TvException && e !is CertificateException) {
                try { send(ip, key, times, false) } catch (e2: Exception) { throw TvController.reachableMsg(e, name) }
            } else throw TvController.reachableMsg(e, name)
        }
        return TvController.opText(op, name)
    }

    private suspend fun send(ip: String, key: String, times: Int, secure: Boolean) {
        val appName = Base64.encodeToString("KoserAI".toByteArray(), Base64.NO_WRAP)
        val token = Prefs.str("tvToken")
        val url = if (secure) "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$appName" + (if (token.isNotBlank()) "&token=$token" else "")
                  else "ws://$ip:8001/api/v2/channels/samsung.remote.control?name=$appName"
        val ws = TvController.Ws(if (secure) TvController.client(ip, 8002) else TvController.client(), url)
        withTimeout(8_000) { ws.open() }
        try {
            // ilk seferde TV ekranında "İzin ver" sorusu çıkar
            withTimeout(35_000) {
                while (true) {
                    val msg = JSONObject(ws.incoming.receive())
                    when (msg.optString("event")) {
                        "ms.channel.connect" -> {
                            msg.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let { Prefs.put("tvToken", it) }
                            break
                        }
                        "ms.channel.unauthorized" -> throw TvException("Televizyonda bağlantıya izin verilmedi. TV ekranındaki soruya İzin Ver de.")
                        "ms.channel.timeOut" -> throw TvException("Televizyon izin sorusuna zamanında cevap verilmedi.")
                    }
                }
            }
            repeat(times) {
                val cmd = JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
                    .put("Cmd", "Click").put("DataOfCmd", key).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))
                ws.send(cmd.toString())
                delay(250)
            }
            delay(300)
        } finally { ws.close() }
    }
}

// ===================================================================== LG webOS

object Lg {
    private fun uriFor(op: String): Pair<String, JSONObject?>? = when (op) {
        "off" -> "ssap://system/turnOff" to null
        "vol_up" -> "ssap://audio/volumeUp" to null
        "vol_down" -> "ssap://audio/volumeDown" to null
        "mute" -> "ssap://audio/setMute" to JSONObject().put("mute", true)
        "ch_up" -> "ssap://tv/channelUp" to null
        "ch_down" -> "ssap://tv/channelDown" to null
        "home" -> "ssap://system.launcher/launch" to JSONObject().put("id", "com.webos.app.home")
        else -> null
    }

    suspend fun run(ctx: Context, ip: String, op: String, value: Int?, name: String): String {
        if (op == "on") {
            val mac = Prefs.str("tvMac")
            if (mac.isBlank()) throw TvException("LG televizyonu uzaktan açmak için ayarlara TV'nin MAC adresini yazmalısın.")
            TvController.wakeOnLan(mac, ip); return TvController.opText(op, name)
        }
        val (uri, payload) = when (op) {
            "set_vol" -> "ssap://audio/setVolume" to JSONObject().put("volume", (value ?: 10).coerceIn(0, 100))
            "source" -> throw TvException("LG'de kaynak değiştirmeyi henüz desteklemiyorum.")
            else -> uriFor(op) ?: throw TvException("Bu komutu bu televizyonda yapamıyorum.")
        }
        val times = if (op == "vol_up" || op == "vol_down") (value ?: 3).coerceIn(1, 30) else 1
        val secure = Prefs.bool("tvSecure")
        try {
            session(ctx, ip, secure, uri, payload, times)
        } catch (e: Exception) {
            if (secure && e !is TvException && e !is CertificateException) {
                try { session(ctx, ip, false, uri, payload, times) } catch (e2: Exception) { throw TvController.reachableMsg(e, name) }
            } else throw TvController.reachableMsg(e, name)
        }
        return TvController.opText(op, name)
    }

    private suspend fun session(ctx: Context, ip: String, secure: Boolean, uri: String, payload: JSONObject?, times: Int) {
        val url = if (secure) "wss://$ip:3001" else "ws://$ip:3000"
        val ws = TvController.Ws(if (secure) TvController.client(ip, 3001) else TvController.client(), url)
        withTimeout(8_000) { ws.open() }
        try {
            register(ctx, ws, signed = true)
            repeat(times) { i ->
                val req = JSONObject().put("id", "k$i").put("type", "request").put("uri", uri)
                if (payload != null) req.put("payload", payload)
                ws.send(req.toString())
                try { withTimeout(4_000) { while (JSONObject(ws.incoming.receive()).optString("id") != "k$i") {} } } catch (_: Exception) {}
            }
        } finally { ws.close() }
    }

    private suspend fun register(ctx: Context, ws: TvController.Ws, signed: Boolean) {
        val pairing = JSONObject(ctx.assets.open("lg_pairing.json").bufferedReader().readText())
        if (!signed) {
            val m = pairing.getJSONObject("manifest")
            m.remove("signed"); m.put("appVersion", "1.0")
            m.getJSONArray("permissions").put("CONTROL_INPUT_TEXT").put("CONTROL_MOUSE_AND_KEYBOARD")
        }
        val key = Prefs.str("lgClientKey")
        if (key.isNotBlank()) pairing.put("client-key", key)
        ws.send(JSONObject().put("type", "register").put("id", "reg0").put("payload", pairing).toString())
        withTimeout(45_000) {
            while (true) {
                val msg = JSONObject(ws.incoming.receive())
                when (msg.optString("type")) {
                    "registered" -> {
                        msg.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotBlank() }?.let { Prefs.put("lgClientKey", it) }
                        return@withTimeout
                    }
                    "error" -> {
                        val err = msg.optString("error")
                        if (signed && !err.contains("cancel", true) && !err.contains("403")) {
                            register(ctx, ws, signed = false); return@withTimeout
                        }
                        throw TvException("Televizyon eşleşmeyi reddetti. TV ekranındaki soruya Kabul Et de.")
                    }
                }
            }
        }
    }
}

// ===================================================================== Roku

object Roku {
    private val KEYS = mapOf(
        "on" to "PowerOn", "off" to "PowerOff", "vol_up" to "VolumeUp", "vol_down" to "VolumeDown", "mute" to "VolumeMute",
        "ch_up" to "ChannelUp", "ch_down" to "ChannelDown", "home" to "Home", "source" to "InputHDMI1"
    )

    suspend fun run(ip: String, op: String, value: Int?, name: String): String {
        if (op == "on" && Prefs.str("tvMac").isNotBlank()) try { TvController.wakeOnLan(Prefs.str("tvMac"), ip) } catch (_: Exception) {}
        if (op == "set_vol") throw TvException("Roku'da sesi tam seviyeye ayarlayamıyorum.")
        val key = KEYS[op] ?: throw TvException("Bu komutu bu televizyonda yapamıyorum.")
        val times = if (op == "vol_up" || op == "vol_down") (value ?: 3).coerceIn(1, 30) else 1
        val c = TvController.client()
        try {
            repeat(times) {
                c.newCall(Request.Builder().url("http://$ip:8060/keypress/$key").post(ByteArray(0).toRequestBody()).build()).execute().close()
                delay(150)
            }
        } catch (e: Exception) { throw TvController.reachableMsg(e, name) }
        return TvController.opText(op, name)
    }
}

// ===================================================================== Kendi API'n (HTTP)

object HttpTv {
    private val FIELDS = mapOf(
        "on" to "httpPowerOn", "off" to "httpPowerOff", "vol_up" to "httpVolUp", "vol_down" to "httpVolDown",
        "mute" to "httpMute", "ch_up" to "httpChUp", "ch_down" to "httpChDown", "set_vol" to "httpVolUp"
    )

    /** Biçim: "POST http://adres/yol {json gövde}"  — yöntem yazılmazsa GET. {value} sayı ile değiştirilir. */
    suspend fun run(op: String, value: Int?, name: String): String {
        val field = FIELDS[op] ?: throw TvException("Bu komut için API adresi tanımlı değil.")
        val line = Prefs.str(field).trim()
        if (line.isBlank()) throw TvException("Ayarlarda bu komut için bir API adresi girilmemiş.")
        if (op == "on" && Prefs.str("tvMac").isNotBlank()) try { TvController.wakeOnLan(Prefs.str("tvMac"), Prefs.str("tvIp")) } catch (_: Exception) {}
        val filled = line.replace("{value}", (value ?: 1).toString())
        val m = Regex("^(GET|POST|PUT|PATCH|DELETE)\\s+(\\S+)\\s*(.*)$", RegexOption.IGNORE_CASE).find(filled)
        val method = m?.groupValues?.get(1)?.uppercase() ?: "GET"
        val url = m?.groupValues?.get(2) ?: filled
        val body = m?.groupValues?.get(3)?.takeIf { it.isNotBlank() }
        val rb = Request.Builder().url(url)
        Prefs.str("httpHeader").split(";", "\n").map { it.trim() }.filter { it.contains(":") }.forEach {
            rb.header(it.substringBefore(":").trim(), it.substringAfter(":").trim())
        }
        val ct = if (body?.trimStart()?.startsWith("{") == true) "application/json" else "text/plain"
        when (method) {
            "GET" -> rb.get()
            "DELETE" -> rb.delete()
            else -> rb.method(method, (body ?: "").toRequestBody(ct.toMediaType()))
        }
        try {
            Net.http.newCall(rb.build()).execute().use { r -> if (!r.isSuccessful) throw TvException("API hata verdi (kod ${r.code}).") }
        } catch (e: Exception) { throw TvController.reachableMsg(e, name) }
        return TvController.opText(op, name)
    }
}
