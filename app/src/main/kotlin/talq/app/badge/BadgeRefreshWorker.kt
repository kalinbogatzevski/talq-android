package talq.app.badge

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.SessionState
import talq.app.appContainer
import java.util.concurrent.TimeUnit

/**
 * Reloads the conversation list about twice an hour (when there is a network) and counts
 * again, so the number on the icon also follows what no push tells the phone: new messages
 * in conversations that only notify on mentions, and things read on another device that had
 * no notification. The system decides when it really runs; a phone that restricts background
 * work for the app runs it later or not at all.
 */
class BadgeRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val container = applicationContext.appContainer
        // On screen the conversation list reloads by itself.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return@withContext Result.success()
        }
        container.restoreIfUnreachable()
        val state = withTimeoutOrNull(SESSION_WAIT_MS) { container.sessionState.first { it !is SessionState.Loading } }
        val session = (state as? SessionState.SignedIn)?.session ?: return@withContext Result.success()
        withTimeoutOrNull(ROOMS_TIMEOUT_MS) { session.rooms.refresh() }
        withTimeoutOrNull(COUNT_TIMEOUT_MS) { container.badge.refreshNow(session) }
        Result.success()
    }

    companion object {
        private const val TAG = "TalqBadge"
        private const val WORK_NAME = "talq.badge"
        private const val SESSION_WAIT_MS = 20_000L
        private const val ROOMS_TIMEOUT_MS = 15_000L
        private const val COUNT_TIMEOUT_MS = 30_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BadgeRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "the periodic recount could not be scheduled", e)
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "the periodic recount could not be cancelled", e)
            }
        }
    }
}
