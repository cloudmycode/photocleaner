package com.maxdevlab.cleaner.photo.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.maxdevlab.cleaner.photo.MainActivity
import com.maxdevlab.cleaner.photo.R
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object ReminderDates {
    fun next(now: ZonedDateTime): ZonedDateTime = now.withDayOfMonth(1).withHour(20).withMinute(0).withSecond(0).withNano(0)
        .let { if (it.isAfter(now)) it else it.plusMonths(1) }
}
object MonthlyReminder {
    private const val TAG = "monthly-cleanup"
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("reminder", false)
    fun allowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("reminder", enabled).apply()
        if (enabled) schedule(context) else WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }
    fun schedule(context: Context) {
        if (!enabled(context) || !allowed(context)) return
        val now = ZonedDateTime.now()
        val next = ReminderDates.next(now)
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis().coerceAtLeast(1000), TimeUnit.MILLISECONDS)
            .addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork("$TAG-${next.toLocalDate()}", ExistingWorkPolicy.KEEP, work)
    }
}
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!MonthlyReminder.enabled(context) || !MonthlyReminder.allowed(context)) return Result.success()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("monthly", context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "monthly")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(context.getString(R.string.reminder_body)).setContentIntent(open).setAutoCancel(true).build()
        try { manager.notify(1, notification) } catch (_: SecurityException) { return Result.success() }
        MonthlyReminder.schedule(context)
        return Result.success()
    }
}
