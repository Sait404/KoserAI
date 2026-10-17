package com.sait.koserai.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import com.sait.koserai.KoserApp
import com.sait.koserai.MainActivity
import com.sait.koserai.R
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Telefonun takvimi (Google Takvim vb. hesaplarla senkron) */
object CalendarData {
    private val tr = Locale("tr")

    fun canRead(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun canWrite(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** dayOffset: 0 bugün, 1 yarın. days: kaç gün */
    fun events(ctx: Context, dayOffset: Int, days: Int): JSONArray {
        val out = JSONArray()
        if (!canRead(ctx)) return out
        val start = Store.startOfDay(dayOffset)
        val end = Store.startOfDay(dayOffset + maxOf(1, days))
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end)
        }.build()
        val fmt = SimpleDateFormat("d MMMM EEEE HH:mm", tr)
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext() && out.length() < 30) {
                    val allDay = c.getInt(3) == 1
                    out.put(JSONObject()
                        .put("title", c.getString(0) ?: "(başlıksız)")
                        .put("begin", c.getLong(1)).put("end", c.getLong(2))
                        .put("allDay", allDay)
                        .put("when", if (allDay) SimpleDateFormat("d MMMM EEEE", tr).format(Date(c.getLong(1))) + " (tüm gün)" else fmt.format(Date(c.getLong(1))))
                        .put("location", c.getString(4) ?: ""))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun writableCalendarId(ctx: Context): Long? {
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE),
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=? AND " + CalendarContract.Calendars.VISIBLE + "=1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null
        )?.use { c ->
            var first: Long? = null; var google: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(1) == 1) return id
                if (first == null) first = id
                if (google == null && c.getString(2) == "com.google") google = id
            }
            return google ?: first
        }
        return null
    }

    /** Doğrudan takvime yazar. Yazamıyorsa null döner (çağıran takvim ekranını açar). */
    fun insert(ctx: Context, title: String, start: Long, durationMin: Int, location: String?, reminderMin: Int?): Long? {
        if (!canWrite(ctx)) return null
        val calId = writableCalendarId(ctx) ?: return null
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + durationMin * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.HAS_ALARM, if (reminderMin != null) 1 else 0)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        if (reminderMin != null) {
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, reminderMin)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        return id
    }

    fun insertIntent(title: String, start: Long, durationMin: Int, location: String?): Intent =
        Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + durationMin * 60_000L)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location ?: "")
}

/** "2026-10-19T10:00" ya da "2026-10-19 10:00" -> epoch ms (yerel saat) */
object When {
    fun parse(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().replace(' ', 'T')
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")) {
            try {
                val f = SimpleDateFormat(p, Locale.US); f.isLenient = false
                val d = f.parse(t.take(p.replace("'", "").length)) ?: continue
                return d.time
            } catch (_: Exception) {}
        }
        return null
    }

    fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(Date())
    fun human(t: Long): String = SimpleDateFormat("d MMMM EEEE HH:mm", Locale("tr")).format(Date(t))
}

/** Hatırlatıcı alarmı (yaklaşık zamanlı; pil dostu) */
object Reminders {
    fun schedule(ctx: Context, r: JSONObject) {
        val at = r.optLong("at"); if (at <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, r.optString("id").hashCode(),
            Intent(ctx, ReminderReceiver::class.java).putExtra("id", r.optString("id")).putExtra("text", r.optString("text")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun cancel(ctx: Context, id: String) {
        val pi = PendingIntent.getBroadcast(ctx, id.hashCode(), Intent(ctx, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
    }

    fun rescheduleAll(ctx: Context) { Store.pendingReminders().forEach { schedule(ctx, it) } }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val text = intent.getStringExtra("text") ?: "Hatırlatma"
        Store.markFired(id)
        Store.log("reminder", "Hatırlattım: $text")
        val pi = PendingIntent.getActivity(ctx, 9, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(NotificationManager::class.java).notify(id.hashCode(),
            NotificationCompat.Builder(ctx, KoserApp.CH_ALERT)
                .setSmallIcon(R.drawable.ic_fg).setContentTitle("Koser hatırlatıyor")
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build())
    }
}

/** Takvim için tarih yardımcıları */
fun Calendar.atStartOfDay(): Calendar = apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
