package talq.app.push

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import talq.app.appContainer
import java.util.concurrent.TimeUnit

/**
 * Brings the app back, whenever there is a network, for a reply typed in a notification that is not in the
 * chat yet: nobody opens the app for it. The sending is the outbox's; this only keeps the process going
 * ([MessageActions.settle]). It knows the reply by its outbox key, never its text: one that is sent, given
 * up or signed out of is simply not there any more.
 */
class ReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val token = inputData.getString(KEY_TOKEN)
        val ref = inputData.getString(KEY_REF)
        if (token == null || ref == null) return@withContext Result.success()
        val done = applicationContext.appContainer.messageActions.settle(token, ref, inputData.getLong(KEY_NID, 0L))
        // It is kept as unsent either way: opening the app tries it again.
        if (done || runAttemptCount >= MAX_RUNS) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "TalqPush"
        private const val KEY_TOKEN = "token"
        private const val KEY_REF = "ref"
        private const val KEY_NID = "nid"
        private const val MAX_RUNS = 12
        private const val BACKOFF_SECONDS = 30L

        fun start(context: Context, token: String, ref: String, nid: Long) {
            val request = OneTimeWorkRequestBuilder<ReplyWorker>()
                .setInputData(Data.Builder().putString(KEY_TOKEN, token).putString(KEY_REF, ref).putLong(KEY_NID, nid).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            // Before Android 12 expedited work is a foreground service with a notification of its own.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            try {
                WorkManager.getInstance(context).enqueueUniqueWork("talq.reply.$ref", ExistingWorkPolicy.KEEP, request.build())
            } catch (e: IllegalStateException) {
                Log.w(TAG, "the work for a reply could not be started", e)
            }
        }
    }
}
