package com.example.expensetracker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Nightly copy into Documents/ExpenseTracker. Never fails loudly. */
class PhoneBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): ListenableWorker.Result {
        try { BackupManager.backupToPhone(applicationContext) } catch (e: Exception) { e.printStackTrace() }
        return ListenableWorker.Result.success()
    }
}

/**
 * Morning upload to the Drive file the user picked. Scheduled with a "needs internet"
 * constraint, so without a connection it simply waits and runs when one is available.
 */
class DriveBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): ListenableWorker.Result {
        return try {
            when (BackupManager.backupToDrive(applicationContext)) {
                BackupManager.DriveOutcome.RETRY ->
                    if (runAttemptCount < BackupManager.MAX_RETRIES_PER_RUN) ListenableWorker.Result.retry()
                    else {
                        BackupManager.noteDriveGaveUp(applicationContext)
                        ListenableWorker.Result.success()   // try again at the next daily run
                    }
                else -> ListenableWorker.Result.success()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ListenableWorker.Result.success()
        }
    }
}
