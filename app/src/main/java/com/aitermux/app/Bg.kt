package com.aitermux

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notif {
    const val CH = "ai_termux"

    fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "AI+Termux", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun openIntent(c: Context): PendingIntent = PendingIntent.getActivity(c, 0,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Posts "reply ready" when the app is not on screen. */
    fun ready(c: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channel(c)
        val n = NotificationCompat.Builder(c, CH).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (Store.lang == "ar") "رد النموذج جاهز" else "Model reply ready")
            .setContentText(text.take(120)).setStyle(NotificationCompat.BigTextStyle().bigText(text.take(500)))
            .setAutoCancel(true).setContentIntent(openIntent(c)).build()
        runCatching { NotificationManagerCompat.from(c).notify(2, n) }
    }
}

/** Keeps the process alive while a model request is in flight, so you can switch to other apps meanwhile. */
class KeepAliveService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notif.channel(this)
        val n = NotificationCompat.Builder(this, Notif.CH).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("AI+Termux")
            .setContentText(if (Store.lang == "ar") "جارٍ انتظار رد النموذج… يمكنك فتح تطبيق آخر" else "Waiting for the model reply… you can use other apps")
            .setOngoing(true).setContentIntent(Notif.openIntent(this)).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        if (wl == null) wl = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aitermux:req").also { it.acquire(15 * 60 * 1000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() { runCatching { wl?.release() }; super.onDestroy() }
}

object Bg {
    private var n = 0
    fun begin(c: Context) { if (n++ == 0) runCatching { ContextCompat.startForegroundService(c, Intent(c, KeepAliveService::class.java)) } }
    fun end(c: Context) { if (n > 0 && --n == 0) runCatching { c.stopService(Intent(c, KeepAliveService::class.java)) } }
}
