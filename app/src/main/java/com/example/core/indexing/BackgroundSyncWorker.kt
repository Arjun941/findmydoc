package com.example.core.indexing

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.core.extraction.LocalDocumentExtractor
import com.example.data.database.DocDatabase
import com.example.data.repository.IndexingRepository
import java.util.concurrent.TimeUnit

class BackgroundSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val tag = "BackgroundSyncWorker"

    override suspend fun doWork(): Result {
        Log.d(tag, "WorkManager periodic sync worker started")
        return try {
            val indexRepo = IndexingRepository.getInstance(applicationContext)

            // Perform automatic incremental sync
            indexRepo.discoverAndIndexFiles(forceRescan = false)
            Log.d(tag, "WorkManager periodic sync finished successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "Background sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "com.seekmydocs.periodic_sync_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                4, TimeUnit.HOURS // run every 4 hours
            )
            .setConstraints(constraints)
            .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }
    }
}
