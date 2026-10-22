package com.hazyoctophant.cardvault.pricing

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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hazyoctophant.cardvault.CardVaultApp
import com.hazyoctophant.cardvault.MainActivity
import com.hazyoctophant.cardvault.R
import com.hazyoctophant.cardvault.ui.formatUsd
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Once a day: pull prices for every owned card from all sources and update the price sheet. */
class PriceRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CardVaultApp).container
        val result = container.prices.refreshAll()
        if (result.cardsTracked > 0 && result.cardsUpdated == 0 && result.errors.isNotEmpty()) {
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        if (container.settings.current.notifyOnRefresh && result.cardsTracked > 0) notify(result.totalBefore, result.totalAfter)
        return Result.success()
    }

    private fun notify(before: Double, after: Double) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val delta = after - before
        val change = if (abs(delta) < 0.005) "no change" else (if (delta > 0) "+" else "−") + formatUsd(abs(delta))
        val intent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Collection value: ${formatUsd(after)}")
            .setContentText("Prices updated ($change since last update)")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(1, notification)
    }

    companion object {
        const val CHANNEL_ID = "price_updates"
        private const val DAILY = "daily-price-refresh"
        private const val NOW = "price-refresh-now"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(CHANNEL_ID, "Price updates", NotificationManager.IMPORTANCE_LOW)
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        }

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(DAILY)
                return
            }
            val request = PeriodicWorkRequestBuilder<PriceRefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<PriceRefreshWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        fun observeNow(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW)
    }
}
