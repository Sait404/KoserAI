package com.sait.koserai.core

import android.content.Context
import android.content.Intent

data class AppEntry(val label: String, val pkg: String, val norm: String)

object Apps {
    /** Sık söylenen adlar -> paket adı (yüklüyse doğrudan kullanılır) */
    private val ALIASES = mapOf(
        "whatsapp" to "com.whatsapp", "vatsap" to "com.whatsapp", "watsap" to "com.whatsapp",
        "vatsapp" to "com.whatsapp", "whatsap" to "com.whatsapp", "wp" to "com.whatsapp", "vp" to "com.whatsapp",
        "instagram" to "com.instagram.android", "insta" to "com.instagram.android", "instegram" to "com.instagram.android",
        "youtube" to "com.google.android.youtube", "yutup" to "com.google.android.youtube", "yutub" to "com.google.android.youtube",
        "youtube music" to "com.google.android.apps.youtube.music",
        "spotify" to "com.spotify.music", "spotifay" to "com.spotify.music",
        "telegram" to "org.telegram.messenger", "tiktok" to "com.zhiliaoapp.musically", "tik tok" to "com.zhiliaoapp.musically",
        "twitter" to "com.twitter.android", "x" to "com.twitter.android", "facebook" to "com.facebook.katana",
        "netflix" to "com.netflix.mediaclient", "snapchat" to "com.snapchat.android", "discord" to "com.discord",
        "gmail" to "com.google.android.gm", "mail" to "com.google.android.gm", "e posta" to "com.google.android.gm",
        "chrome" to "com.android.chrome", "krom" to "com.android.chrome",
        "harita" to "com.google.android.apps.maps", "haritalar" to "com.google.android.apps.maps", "maps" to "com.google.android.apps.maps",
        "play store" to "com.android.vending", "market" to "com.android.vending", "google play" to "com.android.vending",
        "google" to "com.google.android.googlequicksearchbox", "gemini" to "com.google.android.apps.bard",
        "trendyol" to "trendyol.com", "getir" to "com.getir", "yemeksepeti" to "com.inovel.app.yemeksepeti",
        "hepsiburada" to "com.pozitron.hepsiburada", "e devlet" to "tr.gov.turkiye.edevlet.kapisi", "edevlet" to "tr.gov.turkiye.edevlet.kapisi",
    )

    /** Sistem işlevleri (paket adı cihazdan cihaza değişir) */
    private val SYSTEM = mapOf(
        "kamera" to "camera", "galeri" to "gallery", "fotograflar" to "gallery", "resimler" to "gallery",
        "ayarlar" to "settings", "telefon ayarlari" to "settings", "hesap makinesi" to "calculator",
        "takvim" to "calendar", "rehber" to "contacts", "kisiler" to "contacts", "mesajlar" to "sms",
        "telefon" to "dialer", "tarayici" to "browser", "internet" to "browser", "saat" to "clock", "alarm" to "clock",
        "muzik" to "music", "dosyalar" to "files", "dosya yoneticisi" to "files"
    )

    private var cache: List<AppEntry> = emptyList()
    private var loadedAt = 0L

    fun installed(ctx: Context): List<AppEntry> {
        if (System.currentTimeMillis() - loadedAt < 120_000 && cache.isNotEmpty()) return cache
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        cache = pm.queryIntentActivities(i, 0).map {
            val l = it.loadLabel(pm).toString()
            AppEntry(l, it.activityInfo.packageName, TextNorm.basic(l))
        }.distinctBy { it.pkg }
        loadedAt = System.currentTimeMillis()
        return cache
    }

    fun isInstalled(ctx: Context, pkg: String) = installed(ctx).any { it.pkg == pkg }

    sealed class Target {
        data class Pkg(val entry: AppEntry) : Target()
        data class Sys(val kind: String, val label: String) : Target()
    }

    fun find(ctx: Context, spokenRaw: String): Target? {
        val spoken = TextNorm.basic(spokenRaw).trim()
        if (spoken.isBlank()) return null
        val candidates = listOf(spoken, spoken.split(" ").joinToString(" ") { TextNorm.stripSuffix(it) }, spoken.replace(" ", ""))
        val apps = installed(ctx)
        for (c in candidates) {
            ALIASES[c]?.let { pkg -> apps.firstOrNull { it.pkg == pkg }?.let { return Target.Pkg(it) } }
        }
        // yüklü uygulama adıyla birebir / içerme
        for (c in candidates) {
            apps.firstOrNull { it.norm == c || it.norm.replace(" ", "") == c.replace(" ", "") }?.let { return Target.Pkg(it) }
        }
        for (c in candidates) {
            SYSTEM[c]?.let { return Target.Sys(it, c) }
        }
        for (c in candidates) {
            if (c.length >= 3) apps.firstOrNull { it.norm.startsWith(c) || (it.norm.length >= 4 && c.startsWith(it.norm)) }
                ?.let { return Target.Pkg(it) }
        }
        // benzerlik
        var best: AppEntry? = null
        var bestS = 0.0
        for (c in candidates) for (a in apps) {
            val s = TextNorm.similarity(c, a.norm)
            if (s > bestS) { bestS = s; best = a }
        }
        return if (bestS >= 0.72 && best != null) Target.Pkg(best) else null
    }

    fun whatsappPackage(ctx: Context): String? = when {
        isInstalled(ctx, "com.whatsapp") -> "com.whatsapp"
        isInstalled(ctx, "com.whatsapp.w4b") -> "com.whatsapp.w4b"
        else -> null
    }
}
