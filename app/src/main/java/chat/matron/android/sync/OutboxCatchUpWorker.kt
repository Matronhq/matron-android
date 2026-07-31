package chat.matron.android.sync

import android.app.ActivityManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import chat.matron.android.MatronApplication
import chat.matron.android.models.MatronDebug
import java.util.concurrent.TimeUnit

/**
 * Periodic background journal catch-up + outbox flush — the Android analog of
 * iOS's BGAppRefresh task (`chat.matron.refresh`). Without it, a message
 * queued offline only leaves the phone the next time the user opens the app:
 * the sync engine lives in the UI process and dies with it.
 *
 * The worker restores the persisted session, delegates to
 * `AppDependencies.backgroundCatchUp` (bounded — WorkManager expects workers
 * to finish promptly), and never fails the chain: sync problems are the run
 * loop's business, not WorkManager backoff's.
 */
class OutboxCatchUpWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MatronApplication ?: return Result.success()
        val deps = app.dependencies
        val session = runCatching { deps.auth.restoreSession() }.getOrNull() ?: return Result.success()
        runCatching { deps.backgroundCatchUp(session, isAppVisible = ::isAppInForeground) }
            .onFailure { MatronDebug.breadcrumb("OutboxCatchUpWorker: catch-up failed: $it") }
        return Result.success()
    }

    /**
     * Whether the app currently has visible UI. If the user opened the app
     * mid-run, `backgroundCatchUp` must leave the engine running — tearing it
     * down would kill the socket under the live UI (its `sync.start()` call
     * was a no-op against the worker-started engine).
     */
    private fun isAppInForeground(): Boolean {
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val myPid = android.os.Process.myPid()
        return am.runningAppProcesses?.any {
            it.pid == myPid && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } ?: false
    }

    companion object {
        /** Mirrors the iOS BGTaskScheduler identifier for grep-ability. */
        private const val UNIQUE_NAME = "chat.matron.refresh"

        /**
         * Enqueues (or keeps) the periodic catch-up. 15 minutes is
         * WorkManager's floor; the OS batches and defers under Doze, which is
         * fine — this is a catch-up, not a delivery SLA.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OutboxCatchUpWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Sign-out hygiene: a signed-out app has nothing to catch up. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
