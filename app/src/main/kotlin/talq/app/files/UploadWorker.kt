package talq.app.files

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import talq.app.MainActivity
import talq.app.R
import talq.app.SessionState
import talq.app.appContainer
import talq.app.nav.DeepLink

/**
 * Keeps the app alive while files are going out, with a notification ("Uploading 2 files…"),
 * so leaving the app does not stop them. The uploads themselves run in the session
 * (`FileOutbox`); this worker only stays in the foreground until none is active.
 *
 * When the system runs it again after the process died, it sends what was left over
 * ([UploadManager.resumeInterrupted]).
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(notification(applicationContext, 0, 0f))

    override suspend fun doWork(): Result {
        val context = applicationContext
        val container = context.appContainer
        val uploads = container.uploads
        // A leftover of the queue (every send appends one): nothing to keep alive, and no
        // notification to flash.
        val leftovers = uploads.startedFresh && uploads.pending.value.isNotEmpty()
        if (!leftovers && uploads.pending.value.none { it.state.active }) return Result.success()
        goForeground(notification(context, uploads.pending.value.count { it.state.active }, 0f))

        if (leftovers) {
            // A new process: wait for the account, then pick up what the old one left.
            val state = withTimeoutOrNull(SESSION_WAIT_MS) {
                container.sessionState.first { it !is SessionState.Loading }
            }
            val session = (state as? SessionState.SignedIn)?.session
            if (session != null) uploads.resumeInterrupted(session)
        }

        var shown = -1 to -1
        uploads.pending.takeWhile { list -> list.any { it.state.active } }.collect { list ->
            val active = list.filter { it.state.active }
            val fraction = active.map { (it.state as? UploadState.Uploading)?.fraction ?: if (it.state is UploadState.Sharing) 1f else 0f }
                .average().toFloat()
            val now = active.size to (fraction * 20).toInt()
            if (now != shown) {
                shown = now
                goForeground(notification(context, active.size, fraction))
            }
        }

        announceFailures(context, uploads.pending.value)
        return Result.success()
    }

    private suspend fun goForeground(notification: Notification) {
        try {
            setForeground(foregroundInfo(notification))
        } catch (e: Exception) {
            // Started from the background (after a restart) on a system that does not allow a
            // foreground service then: the uploads still run, only without the notification.
            Log.w(TAG, "no foreground notification for the uploads", e)
        }
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }

    companion object {
        private const val TAG = "UploadWorker"
        private const val WORK_NAME = "talq.uploads"
        private const val CHANNEL = "talq.uploads"
        private const val NOTIFICATION_ID = 0x7a10
        private const val FAILED_NOTIFICATION_ID = 0x7a11
        private const val SESSION_WAIT_MS = 60_000L

        /** Make sure the worker is running. Work already running is kept; this one follows it. */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "the upload work could not be started", e)
            }
        }

        private fun ensureChannel(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Uploads")
                    .setDescription("Files being sent")
                    .build(),
            )
        }

        private fun notification(context: Context, count: Int, fraction: Float): Notification {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_talq)
                .setContentTitle(if (count <= 1) "Uploading a file…" else "Uploading $count files…")
                .setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), count == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        /** Nobody is looking and something did not go out: say so, with a way back to it. */
        @SuppressLint("MissingPermission") // checked through areNotificationsEnabled()
        private fun announceFailures(context: Context, pending: List<PendingUpload>) {
            val failed = pending.filter { !it.state.active }
            if (failed.isEmpty()) return
            if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            ensureChannel(context)
            val first = failed.first()
            val open = PendingIntent.getActivity(
                context, FAILED_NOTIFICATION_ID,
                DeepLink.toIntent(context, first.token, first.threadId.takeIf { it > 0 }),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.notify(
                FAILED_NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_talq)
                    .setContentTitle(if (failed.size == 1) "A file wasn't sent" else "${failed.size} files weren't sent")
                    .setContentText("Open the conversation to try again.")
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }
}
