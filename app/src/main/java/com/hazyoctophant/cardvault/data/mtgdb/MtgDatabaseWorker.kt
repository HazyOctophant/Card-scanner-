package com.hazyoctophant.cardvault.data.mtgdb

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
import androidx.work.workDataOf
import com.hazyoctophant.cardvault.CardVaultApp
import java.util.concurrent.TimeUnit

/** Downloads / updates the offline Magic database in the background. */
class MtgDatabaseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CardVaultApp).container
        return try {
            val force = inputData.getBoolean(KEY_FORCE, false)
            val result = container.mtgDb.import(force) { fraction, cards ->
                setProgress(workDataOf(KEY_PROGRESS to fraction, KEY_CARDS to cards))
            }
            scheduleWeekly(applicationContext, container.settings.current.mtgDbAutoUpdate)
            Result.success(workDataOf(KEY_CARDS to result.cards))
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    companion object {
        const val KEY_PROGRESS = "progress"
        const val KEY_CARDS = "cards"
        const val KEY_ERROR = "error"
        private const val KEY_FORCE = "force"
        private const val NOW = "mtg-db-download"
        private const val WEEKLY = "mtg-db-weekly"

        fun downloadNow(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<MtgDatabaseWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_FORCE to force))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        /** Weekly refresh on Wi-Fi while charging, so the big download never uses mobile data. */
        fun scheduleWeekly(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WEEKLY)
                return
            }
            val request = PeriodicWorkRequestBuilder<MtgDatabaseWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(WEEKLY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun observeNow(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW)
    }
}
