package app.mapstotom

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant

/** Posts "QuickGPSFix expires tomorrow" at the time set by [schedule]. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "QuickGPSFix reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Update the TomTom's QuickGPSFix")
            .setContentText("It expires tomorrow. Plug the TomTom in and open MapsToTom.")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    companion object {
        private const val CHANNEL = "quickfix"

        fun schedule(ctx: Context, at: Instant?) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pi)
            if (at != null && at.isAfter(Instant.now())) am.set(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi) // inexact is fine
        }
    }
}
