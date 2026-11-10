package com.shabana.filekhoj.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shabana.filekhoj.MainActivity
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Daily local reminder scan. No document content leaves the device. */
class ExpiryReminderWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.success()
        }

        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Document reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Local reminders for selected document expiry dates."
                }
            )
        }

        val dao = DocumentDatabase.getInstance(applicationContext).documentDao()
        val horizon = System.currentTimeMillis() + REMIND_WITHIN_MILLIS
        dao.getDueReminders(horizon).forEach { reminder ->
            val document = dao.findById(reminder.documentId) ?: run {
                dao.deleteReminder(reminder.documentId)
                return@forEach
            }
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(reminder.dueAt))
            val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                reminder.documentId.toInt(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle(reminder.label)
                .setContentText("${document.displayName} • due ${date}")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${document.displayName}\n${reminder.label}\nDue date: ${date}"))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(reminder.documentId.hashCode(), notification)
            dao.markReminderNotified(reminder.documentId, System.currentTimeMillis())
        }
        Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "filekhoj_document_reminders"
        private const val REMIND_WITHIN_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private const val UNIQUE_WORK_NAME = "filekhoj-document-expiry-reminders"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ExpiryReminderWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
    }
}
