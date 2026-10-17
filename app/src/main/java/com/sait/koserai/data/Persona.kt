package com.sait.koserai.data

import com.sait.koserai.core.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** Üç karakter. Her birinin kendi adı, sesi, rengi ve tavrı var. Ad ve ses kullanıcı tarafından değiştirilebilir. */
data class Persona(
    val id: String,
    val name: String,
    val voice: String,
    val tagline: String,
    val style: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("voice", voice).put("tagline", tagline).put("style", style)

    companion object {
        val DEFAULTS = listOf(
            Persona(
                "koser", "KOSER", "Charon", "DAHA İYİ BİR YARIN İÇİN",
                "Kibar, ölçülü, sakin ve güven veren bir kişisel asistansın. Profesyonel ama sıcak konuşursun. " +
                    "Kullanıcıya saygıyla hitap edersin; \"efendim\" kelimesini ara sıra, doğal yerlerde kullanırsın, her cümlede değil."
            ),
            Persona(
                "ultron", "ULTRON", "Algenib", "SINIRLAR İNSANLAR İÇİNDİR",
                "Soğuk, kendinden emin ve kendine has bir yapay zekâsın. Az, keskin ve etkileyici cümlelerle konuşursun. " +
                    "Hafif üstten bakan, ironik bir mizahın var ama asla hakaret etmez, kaba olmazsın ve her zaman gerçekten yardım edersin. " +
                    "Duygusal süslemelerden kaçınırsın; verimliliği seversin."
            ),
            Persona(
                "nova", "NOVA", "Aoede", "ENERJİ SENDE",
                "Samimi, enerjik, neşeli ve esprili bir asistansın. Kullanıcıyla arkadaş gibi, sıcak ve canlı konuşursun. " +
                    "Motive edersin, gerektiğinde şakalaşırsın ama işini ciddiyetle yaparsın."
            ),
        )

        /** Gemini'nin hazır sesleri (Live API ile kullanılabilir) */
        val VOICES = listOf(
            "Charon" to "Bilgilendirici, tok erkek", "Orus" to "Kararlı erkek", "Iapetus" to "Net erkek",
            "Algenib" to "Pürüzlü, derin erkek", "Fenrir" to "Heyecanlı erkek", "Puck" to "Neşeli erkek",
            "Alnilam" to "Sağlam erkek", "Gacrux" to "Olgun", "Sadaltager" to "Bilgili", "Rasalgethi" to "Bilgilendirici",
            "Enceladus" to "Nefesli, yumuşak", "Umbriel" to "Rahat", "Algieba" to "Pürüzsüz", "Schedar" to "Dengeli",
            "Achird" to "Arkadaşça", "Zubenelgenubi" to "Gündelik",
            "Aoede" to "Ferah kadın", "Kore" to "Kararlı kadın", "Leda" to "Genç kadın", "Zephyr" to "Parlak kadın",
            "Callirrhoe" to "Rahat kadın", "Autonoe" to "Parlak kadın", "Despina" to "Pürüzsüz kadın", "Erinome" to "Net kadın",
            "Laomedeia" to "Neşeli kadın", "Achernar" to "Yumuşak kadın", "Pulcherrima" to "Atılgan kadın",
            "Vindemiatrix" to "Nazik kadın", "Sadachbia" to "Canlı kadın", "Sulafat" to "Sıcak kadın",
        )

        fun all(): List<Persona> {
            val custom = Prefs.all().optJSONObject("personas") ?: JSONObject()
            return DEFAULTS.map { d ->
                val c = custom.optJSONObject(d.id)
                if (c == null) d else d.copy(
                    name = c.optString("name").trim().ifBlank { d.name }.take(16),
                    voice = c.optString("voice").takeIf { v -> VOICES.any { it.first == v } } ?: d.voice,
                )
            }
        }

        fun active(): Persona {
            val id = Prefs.str("persona").ifBlank { "koser" }
            return all().firstOrNull { it.id == id } ?: all()[0]
        }

        fun find(nameOrId: String): Persona? {
            val n = com.sait.koserai.core.TextNorm.basic(nameOrId)
            return all().firstOrNull { it.id == n || com.sait.koserai.core.TextNorm.basic(it.name) == n }
                ?: all().firstOrNull { n.contains(it.id) || n.contains(com.sait.koserai.core.TextNorm.basic(it.name)) }
        }

        fun allJson(): JSONArray = JSONArray().apply { all().forEach { put(it.toJson()) } }

        fun voicesJson(): JSONArray = JSONArray().apply { VOICES.forEach { (v, d) -> put(JSONObject().put("id", v).put("desc", d)) } }
    }
}
