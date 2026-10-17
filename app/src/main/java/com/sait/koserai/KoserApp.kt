package com.sait.koserai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.sait.koserai.core.Prefs

class KoserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE, "Hey Koser dinleme", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Koser arka planda uyandırma cümlesini beklerken görünür."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Koser bildirimleri", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CH_WAKE = "wake"
        const val CH_ALERT = "alert"
        lateinit var instance: KoserApp
            private set
    }
}
