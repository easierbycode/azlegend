package com.azlegend.wear.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.azlegend.wear.AzLegendApp
import com.azlegend.wear.MainActivity
import com.azlegend.wear.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * dataSync foreground service that stays up while [Downloads] has work, showing one progress
 * notification (surfaced on the watch face as an ongoing activity) with a cancel action.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var downloads: Downloads
    private var observing = false
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        downloads = AzLegendApp.container(this).downloads
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ALBUM_ID)?.let(downloads::cancelAlbum)
        }
        val active = activeDownload()
        if (!goForeground(buildNotification(active))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active == null) {
            // The work finished (or was cancelled) before this start was delivered.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                downloads.progress.collect {
                    val current = activeDownload()
                    if (current == null) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelfResult(lastStartId)
                    } else {
                        runCatching { notificationManager().notify(NOTIFICATION_ID, buildNotification(current)) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ ends dataSync services after their daily budget; stop cleanly instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        downloads.cancelAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun activeDownload(): DownloadProgress? {
        val all = downloads.progress.value.values
        return all.firstOrNull { it.state == DownloadState.RUNNING } ?: all.firstOrNull { it.state == DownloadState.QUEUED }
    }

    private fun goForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException or a missing permission: the transfer still runs
        // in the app scope, only without the radio/process guarantees.
        false
    }

    private fun buildNotification(progress: DownloadProgress?): Notification {
        val albumTitle = progress?.albumTitle ?: getString(R.string.channel_downloads)
        val detail = when {
            progress == null || progress.state == DownloadState.QUEUED -> getString(R.string.album_queued)
            progress.trackCount > 0 -> "${progress.trackIndex + 1}/${progress.trackCount} · ${progress.trackTitle}"
            else -> progress.trackTitle
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.notification_downloading, albumTitle))
            .setContentText(detail)
            .setProgress(100, progress?.percent ?: 0, progress == null || progress.state == DownloadState.QUEUED)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (progress != null) {
            val cancel = PendingIntent.getService(
                this, 1,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ALBUM_ID, progress.albumId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, getString(R.string.action_cancel), cancel)
        }
        val status = if (progress == null) albumTitle else "${progress.percent}% · $albumTitle"
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_stat_download)
            .setTouchIntent(open)
            .setStatus(Status.forPart(Status.TextPart(status)))
            .build()
            .apply(this)
        return builder.build()
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 2001
        const val ACTION_CANCEL = "com.azlegend.wear.action.CANCEL_DOWNLOAD"
        const val EXTRA_ALBUM_ID = "albumId"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
