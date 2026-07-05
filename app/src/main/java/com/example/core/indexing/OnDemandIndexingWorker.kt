package com.example.core.indexing

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.repository.IndexingRepository

class OnDemandIndexingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val tag = "OnDemandIndexingWorker"

    override suspend fun doWork(): Result {
        Log.d(tag, "OnDemandIndexingWorker started")
        val action = inputData.getString("action") ?: "index"
        val forceRescan = inputData.getBoolean("force_rescan", false)

        return try {
            val indexRepo = IndexingRepository.getInstance(applicationContext)
            if (action == "sandbox") {
                indexRepo.createSandboxFiles()
            } else {
                indexRepo.discoverAndIndexFiles(forceRescan)
            }
            Log.d(tag, "OnDemandIndexingWorker finished successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "OnDemandIndexingWorker failed", e)
            Result.failure()
        }
    }
}
