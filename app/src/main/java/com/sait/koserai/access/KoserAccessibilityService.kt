package com.sait.koserai.access

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * İsteğe bağlı yardımcı servis. Yalnızca iki iş yapar:
 *  1) Koser bir WhatsApp mesajı hazırladığında "Gönder" düğmesine basar (yalnızca o an, yalnızca WhatsApp'ta).
 *  2) Sesli komutla ekran görüntüsü / kilitleme / ana ekran gibi sistem tuşlarını uygular.
 * Ekran içeriğini okumaz, kaydetmez, hiçbir yere göndermez.
 */
class KoserAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > pendingSendUntil) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        trySend(pkg)
    }

    private fun trySend(pkg: String) {
        val root = rootInActiveWindow ?: return
        val btn: AccessibilityNodeInfo? =
            root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
                ?: root.findAccessibilityNodeInfosByText("Gönder").firstOrNull { it.isClickable }
                ?: root.findAccessibilityNodeInfosByText("Send").firstOrNull { it.isClickable }
        if (btn != null && btn.isEnabled) {
            var n: AccessibilityNodeInfo? = btn
            while (n != null && !n.isClickable) n = n.parent
            if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                pendingSendUntil = 0
                val cb = onSent; onSent = null
                Handler(Looper.getMainLooper()).post { cb?.invoke() }
            }
        }
    }

    companion object {
        @Volatile var instance: KoserAccessibilityService? = null
        @Volatile var pendingSendUntil = 0L
        var onSent: (() -> Unit)? = null

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, KoserAccessibilityService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, true) }
        }

        fun armWhatsAppSend(done: () -> Unit) {
            onSent = done
            pendingSendUntil = System.currentTimeMillis() + 15_000
            // WhatsApp zaten açıksa olay gelmeyebilir; kısa aralıklarla dene
            val h = Handler(Looper.getMainLooper())
            for (d in longArrayOf(1200, 2200, 3500, 5000)) h.postDelayed({
                val s = instance
                if (s != null && System.currentTimeMillis() < pendingSendUntil) {
                    val pkg = s.rootInActiveWindow?.packageName?.toString()
                    if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") s.trySend(pkg)
                }
            }, d)
        }

        fun global(op: String): Boolean {
            val s = instance ?: return false
            val a = when (op) {
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return false
                "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
                else -> return false
            }
            return s.performGlobalAction(a)
        }
    }
}
