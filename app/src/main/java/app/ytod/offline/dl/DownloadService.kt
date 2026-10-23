package app.ytod.offline.dl

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ytod.offline.App
import app.ytod.offline.R
import app.ytod.offline.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps downloads going while the app is in the background, with a progress notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(DownloadManager.jobs.value), type)
        if (!watching) {
            watching = true
            scope.launch {
                DownloadManager.jobs.collect { jobs ->
                    if (jobs.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 800) {
                            lastNotify = now
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(jobs))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(jobs: List<DlJob>): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val running = jobs.filter { it.state == JobState.RUNNING }
        val title = when {
            jobs.isEmpty() -> "Getting ready…"
            jobs.size == 1 -> jobs[0].title.ifEmpty { "Downloading video" }
            else -> "Downloading ${jobs.size} videos"
        }
        val avg = if (running.isEmpty()) 0f else running.map { it.progress }.average().toFloat()
        val text = when {
            jobs.size == 1 -> jobs[0].stage.ifEmpty { "${(avg * 100).toInt()}%" }
            running.isNotEmpty() -> "${running.size} downloading • ${jobs.size - running.size} waiting"
            else -> "Waiting…"
        }
        return NotificationCompat.Builder(this, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, (avg * 100).toInt(), running.isEmpty() || avg <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }
    }
}
