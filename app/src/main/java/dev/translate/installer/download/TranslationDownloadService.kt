package dev.translate.installer.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.translate.installer.MainActivity
import dev.translate.installer.data.remote.*
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

/** Only downloads into private storage; never opens a game directory or installs a bundle. */
class TranslationDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transfer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var profile: GameProfile? = null
    private var timedOut = false
    private var requestEpoch = 0L
    private val notifications
        get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            BackgroundDownloads.cancel(this)
            return START_NOT_STICKY
        }
        val selected = intent?.getStringExtra("profile")?.let(GameProfile::fromId)
        if (selected == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val epoch = intent.getLongExtra("epoch", 0)
        if (!BackgroundDownloads.attachService(epoch)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        requestEpoch = epoch
        if (Build.VERSION.SDK_INT >= 26)
            notifications.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Download de traduções",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        try {
            val initial = DownloadSnapshot(profile = selected, status = DownloadStatus.CHECKING)
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(
                    ID,
                    notification(initial),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            else startForeground(ID, notification(initial))
        } catch (_: RuntimeException) {
            BackgroundDownloads.publish(
                DownloadSnapshot(
                    profile = selected,
                    status = DownloadStatus.PAUSED,
                    failure = BundleFailureCode.DOWNLOAD_FAILED,
                )
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (transfer?.isActive == true && profile?.bundleProfile == selected.bundleProfile)
            return START_REDELIVER_INTENT
        val previous = transfer
        previous?.cancel()
        profile = selected
        timedOut = false
        transfer =
            scope.launch {
                previous?.join()
                val lock =
                    (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "FateCafe:download",
                    )
                wakeLock = lock
                lock.acquire(6 * 60 * 60 * 1000L)
                try {
                    runDownload(selected, epoch)
                } finally {
                    if (lock.isHeld) lock.release()
                    if (profile == selected && isActive) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf(startId)
                    }
                }
            }
        return START_REDELIVER_INTENT
    }

    private suspend fun runDownload(selected: GameProfile, epoch: Long) {
        val client = GitHubReleaseClient()
        var snapshot = DownloadSnapshot(profile = selected, status = DownloadStatus.CHECKING)
        fun update(value: DownloadSnapshot) {
            snapshot = value
            if (!BackgroundDownloads.publish(value, epoch))
                throw CancellationException("Superseded download")
            notifications.notify(ID, notification(value))
        }
        for (attempt in 1..3) {
            update(
                snapshot.copy(
                    status = DownloadStatus.CHECKING,
                    attempt = attempt,
                    failure = null,
                    bytesPerSecond = null,
                    remainingSeconds = null,
                )
            )
            try {
                val asset = client.latest(selected)
                val checkpoint =
                    withContext(Dispatchers.IO) {
                        ResumeCheckpoint(
                            BackgroundDownloads.root(this@TranslationDownloadService),
                            selected,
                        )
                    }
                val meter = TransferMeter()
                update(snapshot.copy(asset = asset, bytes = 0, status = DownloadStatus.DOWNLOADING))
                withContext(Dispatchers.IO) {
                    BackgroundDownloads.filesMutex.withLock {
                        checkpoint.prepare(asset)
                        ensureSufficientStorage(
                            checkpoint.partial.parentFile!!,
                            (asset.size - checkpoint.partial.length()).coerceAtLeast(0),
                            ImportLimits(),
                        )
                        client.download(
                            selected,
                            asset,
                            checkpoint.partial,
                            DownloadProgressSink { bytes, total ->
                                val (speed, eta) = meter.sample(bytes, total)
                                val next =
                                    snapshot.copy(
                                        status = DownloadStatus.DOWNLOADING,
                                        bytes = bytes,
                                        bytesPerSecond = speed,
                                        remainingSeconds = eta,
                                    )
                                // StateFlow and NotificationManager are thread-safe; network never
                                // runs
                                // on Main.
                                snapshot = next
                                if (!BackgroundDownloads.publish(next, epoch))
                                    throw CancellationException("Superseded download")
                                notifications.notify(ID, notification(next))
                            },
                            checkpoint,
                        )
                    }
                }
                update(
                    snapshot.copy(
                        status = DownloadStatus.COMPLETE,
                        bytes = asset.size,
                        remainingSeconds = 0,
                    )
                )
                return
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val code =
                    (exception as? BundleValidationException)?.code ?: BundleFailureCode.IO_ERROR
                val retryable =
                    code in
                        setOf(
                            BundleFailureCode.NETWORK_UNAVAILABLE,
                            BundleFailureCode.DOWNLOAD_FAILED,
                            BundleFailureCode.DOWNLOAD_SIZE_MISMATCH,
                        )
                if (retryable && attempt < 3) {
                    update(
                        snapshot.copy(
                            status = DownloadStatus.RETRYING,
                            failure = null,
                            bytesPerSecond = null,
                            remainingSeconds = null,
                        )
                    )
                    delay(attempt * 2000L)
                } else {
                    update(
                        snapshot.copy(
                            status = DownloadStatus.PAUSED,
                            failure = code,
                            bytesPerSecond = null,
                            remainingSeconds = null,
                        )
                    )
                    return
                }
            }
        }
    }

    private fun notification(snapshot: DownloadSnapshot): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else Notification.Builder(this)
        val target =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("download_profile", snapshot.profile?.profileId)
        val open =
            PendingIntent.getActivity(
                this,
                0,
                target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, TranslationDownloadService::class.java).setAction("cancel"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val title =
            when (snapshot.status) {
                DownloadStatus.COMPLETE -> "Download concluído"
                DownloadStatus.PAUSED -> "Download interrompido · toque para retomar"
                DownloadStatus.RETRYING -> "Conexão interrompida · tentando novamente"
                DownloadStatus.CHECKING -> "Buscando a tradução"
                else -> "Baixando tradução"
            }
        val detail = buildString {
            append(snapshot.profile?.displayName ?: "Fate Café")
            snapshot.asset?.let {
                append("\n${transferBytes(snapshot.bytes)} / ${transferBytes(it.size)}")
            }
            snapshot.bytesPerSecond?.let { append(" · ${transferBytes(it.toLong())}/s") }
            snapshot.remainingSeconds
                ?.takeIf { snapshot.active }
                ?.let { append(" · ${transferEta(it)}") }
        }
        builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.active)
            .setAutoCancel(!snapshot.active)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (snapshot.active) {
            val total = snapshot.asset?.size ?: 0
            val percent =
                if (total > 0) (snapshot.bytes * 100 / total).toInt().coerceIn(0, 100) else 0
            builder
                .setProgress(
                    100,
                    percent,
                    total <= 0 || snapshot.status != DownloadStatus.DOWNLOADING,
                )
                .addAction(Notification.Action.Builder(null, "Cancelar", cancel).build())
        }
        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        transfer?.cancel()
        BackgroundDownloads.publish(
            BackgroundDownloads.state.value.copy(
                status = DownloadStatus.PAUSED,
                failure = BundleFailureCode.DOWNLOAD_FAILED,
            ),
            requestEpoch,
        )
        notifications.notify(ID, notification(BackgroundDownloads.state.value))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        if (!timedOut && BackgroundDownloads.state.value.active)
            BackgroundDownloads.publish(
                BackgroundDownloads.state.value.copy(status = DownloadStatus.PAUSED),
                requestEpoch,
            )
        if (BackgroundDownloads.state.value.status == DownloadStatus.CANCELLED)
            notifications.cancel(ID)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "translation_download"
        private const val ID = 41

        internal fun clearNotification(context: android.content.Context) {
            context.getSystemService(NotificationManager::class.java).cancel(ID)
        }
    }
}
