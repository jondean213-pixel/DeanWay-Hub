package com.deanwaylabs.quietos.network.cards

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The backstop — APP-CARD-ROUTE-DESIGN §4 layer 3.
 *
 * Every 15 minutes (WorkManager's floor) the app re-pulls its cards:
 * if the listener died with the process AND no FCM tick landed, a
 * pending card still surfaces within ~15 minutes — and always before
 * its 2-hour expiry. Degradation is "later and visible," never
 * silent. Scheduled once from the hub; KEEP policy means reopens
 * never reset the cadence.
 */
class CardSweepWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            CardRelay.sweep(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "quietos_card_sweep"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CardSweepWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
