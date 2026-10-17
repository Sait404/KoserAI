package com.sait.koserai.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

data class Contact(val name: String, val number: String, val tokens: List<String>)

/** Bir cümlenin içinde geçen kişiyi bulur: "Ahmet'e merhaba yaz" -> Ahmet, kalan kısım "merhaba yaz". */
data class ContactHit(val contact: Contact, val start: Int, val end: Int)

object Contacts {
    private var cache: List<Contact> = emptyList()
    private var loadedAt = 0L

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun all(ctx: Context): List<Contact> {
        if (!hasPermission(ctx)) return emptyList()
        if (System.currentTimeMillis() - loadedAt < 60_000 && cache.isNotEmpty()) return cache
        val list = ArrayList<Contact>()
        val seen = HashSet<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val key = TextNorm.basic(name)
                if (key.isBlank() || !seen.add(key)) continue
                list.add(Contact(name, num, key.split(" ")))
            }
        }
        cache = list
        loadedAt = System.currentTimeMillis()
        return list
    }

    private fun tokenMatches(spoken: String, nameTok: String): Boolean {
        if (spoken == nameTok) return true
        if (spoken.length < 2 || nameTok.length < 2) return false
        // çekim eki: ahmete, ahmeti, ahmetle, annemi, anneme
        if (spoken.startsWith(nameTok) && spoken.length - nameTok.length <= 4) return true
        if (TextNorm.stripSuffix(spoken) == nameTok) return true
        // telaffuz/yazım farkı
        return nameTok.length >= 4 && TextNorm.similarity(spoken, nameTok) >= 0.8
    }

    /** tokens: normalize edilmiş kelimeler. En uzun eşleşen kişi adını döner. */
    fun findIn(ctx: Context, tokens: List<String>, skip: Set<String> = emptySet()): ContactHit? {
        val contacts = all(ctx)
        var best: ContactHit? = null
        var bestScore = 0
        for (ct in contacts) {
            val nt = ct.tokens
            for (i in tokens.indices) {
                if (tokens[i] in skip) continue
                // tam ad (tüm kelimeler sırayla)
                var k = 0
                while (k < nt.size && i + k < tokens.size && tokenMatches(tokens[i + k], nt[k])) k++
                val score = when {
                    k == nt.size -> 100 + nt.size * 10 + (if (tokens[i] == nt[0]) 5 else 0)
                    k >= 1 -> 10 + k * 10 + (if (tokens[i] == nt[0]) 5 else 0)
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = ContactHit(ct, i, i + maxOf(k, 1))
                }
            }
        }
        return best
    }

    /** "0532 123 45 67" -> "905321234567" (WhatsApp uluslararası biçim ister) */
    fun toInternational(raw: String): String {
        var d = raw.filter { it.isDigit() || it == '+' }
        if (d.startsWith("+")) return d.drop(1)
        d = d.filter { it.isDigit() }
        if (d.startsWith("00")) return d.drop(2)
        if (d.length == 11 && d.startsWith("0")) return "90" + d.drop(1)
        if (d.length == 10 && d.startsWith("5")) return "90$d"
        return d
    }
}
