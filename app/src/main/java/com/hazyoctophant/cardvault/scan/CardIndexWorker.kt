package com.hazyoctophant.cardvault.scan

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
import com.hazyoctophant.cardvault.model.Game
import java.util.concurrent.TimeUnit

/** Downloads (or refreshes) the visual card library for the chosen games. */
class CardIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as CardVaultApp).container.cardIndex
        val games = inputData.getStringArray(KEY_GAMES)?.mapNotNull { n -> Game.entries.firstOrNull { it.name == n } }
            ?: repo.installed.value.keys.toList()
        return try {
            games.forEachIndexed { i, game ->
                repo.download(game) { f ->
                    setProgress(workDataOf(KEY_GAME to game.name, KEY_PROGRESS to (i + f) / games.size))
                }
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    companion object {
        const val KEY_GAMES = "games"
        const val KEY_GAME = "game"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val NOW = "card-index-download"
        private const val WEEKLY = "card-index-weekly"

        fun download(context: Context, games: List<Game>) {
            val request = OneTimeWorkRequestBuilder<CardIndexWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_GAMES to games.map { it.name }.toTypedArray()))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /** New sets come out regularly; refresh installed libraries weekly on Wi-Fi. */
        fun scheduleWeekly(context: Context) {
            val request = PeriodicWorkRequestBuilder<CardIndexWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WEEKLY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun observe(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW)
    }
}
