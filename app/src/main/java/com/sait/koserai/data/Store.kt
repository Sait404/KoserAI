package com.sait.koserai.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Koser'in kalıcı hafızası (yalnızca telefonda): görevler, hatırlatıcılar, kullanıcı hakkında bilgiler,
 * yaptığı işlerin günlüğü ve son konuşmalar.
 */
object Store {
    private lateinit var file: File
    private var root = JSONObject()
    private val listeners = ArrayList<() -> Unit>()
    private val tr = Locale("tr")

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "koser_data.json")
        root = try { JSONObject(file.readText()) } catch (e: Exception) { JSONObject() }
        for (k in listOf("tasks", "reminders", "memories", "log", "history")) if (!root.has(k)) root.put(k, JSONArray())
    }

    fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    @Synchronized
    private fun save() {
        try { file.writeText(root.toString()) } catch (_: Exception) {}
        val ls = synchronized(listeners) { listeners.toList() }
        ls.forEach { try { it() } catch (_: Exception) {} }
    }

    private fun arr(k: String): JSONArray = root.getJSONArray(k)
    private fun newId() = System.currentTimeMillis().toString(36) + (100..999).random()

    @Synchronized fun snapshot(): JSONObject = JSONObject(root.toString())

    // ------------------------------------------------------------ görevler
    @Synchronized
    fun addTask(title: String, due: String?): JSONObject {
        val t = JSONObject().put("id", newId()).put("title", title.trim()).put("done", false).put("created", System.currentTimeMillis())
        if (!due.isNullOrBlank()) t.put("due", due)
        arr("tasks").put(t); save(); return t
    }

    @Synchronized
    fun completeTask(query: String): JSONObject? {
        val t = findBest(arr("tasks"), "title", query) { !it.optBoolean("done") } ?: return null
        t.put("done", true).put("doneAt", System.currentTimeMillis()); save(); return t
    }

    @Synchronized
    fun deleteTask(id: String) { removeWhere(arr("tasks")) { it.optString("id") == id }; save() }

    @Synchronized
    fun toggleTask(id: String) {
        val a = arr("tasks")
        for (i in 0 until a.length()) {
            val t = a.getJSONObject(i)
            if (t.optString("id") == id) {
                val d = !t.optBoolean("done"); t.put("done", d); if (d) t.put("doneAt", System.currentTimeMillis()) else t.remove("doneAt")
            }
        }
        save()
    }

    @Synchronized
    fun openTasks(): List<JSONObject> = list(arr("tasks")).filter { !it.optBoolean("done") }

    // ------------------------------------------------------------ hatırlatıcılar
    @Synchronized
    fun addReminder(text: String, at: Long): JSONObject {
        val r = JSONObject().put("id", newId()).put("text", text.trim()).put("at", at).put("fired", false)
        arr("reminders").put(r); save(); return r
    }

    @Synchronized
    fun markFired(id: String) {
        list(arr("reminders")).firstOrNull { it.optString("id") == id }?.put("fired", true); save()
    }

    @Synchronized
    fun deleteReminder(id: String) { removeWhere(arr("reminders")) { it.optString("id") == id }; save() }

    @Synchronized
    fun pendingReminders(): List<JSONObject> = list(arr("reminders")).filter { !it.optBoolean("fired") }.sortedBy { it.optLong("at") }

    // ------------------------------------------------------------ hafıza
    @Synchronized
    fun remember(fact: String): JSONObject {
        val f = fact.trim()
        list(arr("memories")).firstOrNull { it.optString("text").equals(f, true) }?.let { return it }
        val m = JSONObject().put("id", newId()).put("text", f).put("t", System.currentTimeMillis())
        arr("memories").put(m)
        while (arr("memories").length() > 80) arr("memories").remove(0)
        save(); return m
    }

    @Synchronized
    fun forget(query: String): JSONObject? {
        val m = findBest(arr("memories"), "text", query) { true } ?: return null
        removeWhere(arr("memories")) { it.optString("id") == m.optString("id") }; save(); return m
    }

    @Synchronized
    fun deleteMemory(id: String) { removeWhere(arr("memories")) { it.optString("id") == id }; save() }

    @Synchronized
    fun memories(): List<String> = list(arr("memories")).map { it.optString("text") }

    // ------------------------------------------------------------ günlük (Koser ne yaptı)
    @Synchronized
    fun log(kind: String, text: String) {
        arr("log").put(JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("text", text))
        while (arr("log").length() > 300) arr("log").remove(0)
        save()
    }

    @Synchronized
    fun logToday(): List<JSONObject> {
        val start = startOfDay(0)
        return list(arr("log")).filter { it.optLong("t") >= start }
    }

    // ------------------------------------------------------------ konuşma geçmişi
    @Synchronized
    fun addHistory(role: String, text: String) {
        if (text.isBlank()) return
        arr("history").put(JSONObject().put("t", System.currentTimeMillis()).put("role", role).put("text", text.take(600)))
        while (arr("history").length() > 60) arr("history").remove(0)
        try { file.writeText(root.toString()) } catch (_: Exception) {}
    }

    @Synchronized
    fun recentHistory(n: Int): List<JSONObject> = list(arr("history")).takeLast(n)

    @Synchronized
    fun clearHistory() { root.put("history", JSONArray()); save() }

    // ------------------------------------------------------------ yardımcılar
    fun startOfDay(offset: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offset); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun fmtTime(t: Long): String = SimpleDateFormat("d MMMM EEEE HH:mm", tr).format(Date(t))

    private fun list(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }

    private fun removeWhere(a: JSONArray, pred: (JSONObject) -> Boolean) {
        for (i in a.length() - 1 downTo 0) if (pred(a.getJSONObject(i))) a.remove(i)
    }

    private fun findBest(a: JSONArray, field: String, query: String, filter: (JSONObject) -> Boolean): JSONObject? {
        val q = com.sait.koserai.core.TextNorm.basic(query)
        var best: JSONObject? = null; var bestS = 0.0
        for (o in list(a)) {
            if (!filter(o)) continue
            val v = com.sait.koserai.core.TextNorm.basic(o.optString(field))
            val s = when {
                v == q -> 1.0
                v.contains(q) || q.contains(v) -> 0.9
                else -> {
                    val qw = q.split(" ").toSet(); val vw = v.split(" ").toSet()
                    val common = qw.intersect(vw).count { it.length > 2 }
                    maxOf(com.sait.koserai.core.TextNorm.similarity(q, v), common.toDouble() / maxOf(1, qw.size))
                }
            }
            if (s > bestS) { bestS = s; best = o }
        }
        return if (bestS >= 0.45) best else null
    }
}
