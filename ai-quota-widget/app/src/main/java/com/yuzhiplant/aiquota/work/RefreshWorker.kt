package com.yuzhiplant.aiquota.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.providers.QuotaRepository
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        QuotaRepository.refreshAll(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}

object RefreshScheduler {
    private const val PERIODIC = "quota_periodic"
    private const val ONE_SHOT = "quota_now"

    private val network = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 排定定期更新；replace=true 時套用新的間隔（設定變更後呼叫）。Android 最短 15 分鐘。 */
    fun ensurePeriodic(context: Context, replace: Boolean = false) {
        val minutes = Settings(context).refreshMinutes.toLong().coerceAtLeast(15)
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    fun refreshNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, req)
    }
}
