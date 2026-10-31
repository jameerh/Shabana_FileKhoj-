package com.shabana.filekhoj.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

class ScheduledBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val root = File(applicationContext.filesDir, "backups").apply { mkdirs() }
        val destination = File(root, "filekhoj-backup-latest.zip")
        val temporary = File(root, "filekhoj-backup-working.zip")
        return try {
            BackupRestoreManager(applicationContext).createBackup(temporary)
            if (!temporary.isFile || temporary.length() <= 0L) {
                throw IllegalStateException("Backup archive was not created.")
            }
            if (destination.exists() && !destination.delete()) {
                throw IllegalStateException("Could not rotate the previous backup.")
            }
            check(temporary.renameTo(destination)) { "Could not finalize the backup archive." }
            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                .putString(KEY_LAST_ERROR, "")
                .apply()
            Result.success()
        } catch (error: Exception) {
            temporary.delete()
            applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_ERROR, error.message ?: error.javaClass.simpleName)
                .apply()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val PREFS = "filekhoj_backup_settings"
        const val KEY_INTERVAL_DAYS = "interval_days"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
        private const val WORK_NAME = "filekhoj-periodic-backup"

        fun schedule(context: Context, intervalDays: Int) {
            require(intervalDays in setOf(1, 7, 30))
            val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(
                intervalDays.toLong(), TimeUnit.DAYS
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            ).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_INTERVAL_DAYS, intervalDays.toLong())
                .apply()
        }

        fun disable(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_INTERVAL_DAYS).apply()
        }
    }
}
