package dev.translate.installer.data.remote

import dev.translate.installer.domain.GameProfile
import dev.translate.installer.security.BundleFailureCode
import java.util.Locale

enum class DownloadStatus {
    IDLE,
    CHECKING,
    DOWNLOADING,
    RETRYING,
    COMPLETE,
    PAUSED,
    CANCELLED,
}

data class DownloadSnapshot(
    val profile: GameProfile? = null,
    val asset: ReleaseAsset? = null,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val bytes: Long = 0,
    val bytesPerSecond: Double? = null,
    val remainingSeconds: Long? = null,
    val failure: BundleFailureCode? = null,
    val attempt: Int = 1,
) {
    val active: Boolean
        get() =
            status in
                setOf(DownloadStatus.CHECKING, DownloadStatus.DOWNLOADING, DownloadStatus.RETRYING)
}

/** Rate uses only newly received bytes. A restored prefix must not inflate the ETA. */
class TransferMeter {
    private var lastBytes: Long? = null
    private var lastTime: Long? = null
    private var speed: Double? = null

    fun sample(bytes: Long, total: Long, nowNanos: Long = System.nanoTime()): Pair<Double?, Long?> {
        val previousBytes = lastBytes
        val previousTime = lastTime
        if (
            previousBytes != null &&
                previousTime != null &&
                bytes >= previousBytes &&
                nowNanos > previousTime
        ) {
            val seconds = (nowNanos - previousTime) / 1_000_000_000.0
            if (seconds >= .2) {
                val current = (bytes - previousBytes) / seconds
                speed = speed?.let { it * .65 + current * .35 } ?: current
                lastBytes = bytes
                lastTime = nowNanos
            }
        } else {
            lastBytes = bytes
            lastTime = nowNanos
            speed = null
        }
        val rate = speed?.takeIf { it.isFinite() && it >= 1.0 }
        val eta = rate?.let { ((total - bytes).coerceAtLeast(0) / it).toLong() }
        return rate to eta
    }
}

fun transferBytes(bytes: Long): String =
    when {
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 ->
            String.format(Locale.forLanguageTag("pt-BR"), "%.1f KB", bytes / 1_000.0)
        bytes < 1_000_000_000 ->
            String.format(Locale.forLanguageTag("pt-BR"), "%.1f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.forLanguageTag("pt-BR"), "%.2f GB", bytes / 1_000_000_000.0)
    }

fun transferEta(seconds: Long): String =
    when {
        seconds < 60 -> "menos de 1 min"
        seconds < 3600 -> "${(seconds+59)/60} min"
        else -> "${seconds/3600} h ${(seconds%3600)/60} min"
    }
