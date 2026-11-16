package dev.translate.installer.download

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.translate.installer.data.importer.BundleSourceResult
import dev.translate.installer.data.remote.*
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.security.BundleFailureCode
import dev.translate.installer.security.BundleValidationException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Application-scoped transport state. Closing an Activity does not cancel the service. */
object BackgroundDownloads {
    private val mutable = MutableStateFlow(DownloadSnapshot())
    val state = mutable.asStateFlow()
    internal val filesMutex = Mutex()
    @Volatile
    internal var epoch = 0L
        private set

    fun root(context: Context) = File(context.filesDir, "resumable_downloads")

    @Synchronized
    fun restore(context: Context) {
        if (mutable.value.status != DownloadStatus.IDLE) return
        val prefs = context.getSharedPreferences("download_session", Context.MODE_PRIVATE)
        val profile = prefs.getString("profile", null)?.let(GameProfile::fromId) ?: return
        mutable.value = DownloadSnapshot(profile = profile, status = DownloadStatus.PAUSED)
    }

    @Synchronized
    fun begin(context: Context, profile: GameProfile) {
        val current = mutable.value
        if (current.active && current.profile?.bundleProfile == profile.bundleProfile) return
        epoch++
        mutable.value = DownloadSnapshot(profile = profile, status = DownloadStatus.CHECKING)
        context
            .getSharedPreferences("download_session", Context.MODE_PRIVATE)
            .edit()
            .putString("profile", profile.profileId)
            .apply()
        val intent =
            Intent(context, TranslationDownloadService::class.java)
                .putExtra("profile", profile.profileId)
                .putExtra("epoch", epoch)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (_: RuntimeException) {
            publish(
                DownloadSnapshot(
                    profile = profile,
                    status = DownloadStatus.PAUSED,
                    failure = BundleFailureCode.DOWNLOAD_FAILED,
                )
            )
        }
    }

    @Synchronized
    internal fun attachService(expectedEpoch: Long): Boolean {
        if (epoch == 0L) epoch = expectedEpoch
        return epoch == expectedEpoch
    }

    @Synchronized
    internal fun publish(value: DownloadSnapshot, expectedEpoch: Long = epoch): Boolean {
        if (epoch != expectedEpoch) return false
        mutable.value = value
        return true
    }

    @Synchronized
    fun cancel(context: Context) {
        val current = mutable.value
        epoch++
        mutable.value = current.copy(status = DownloadStatus.CANCELLED)
        context.stopService(Intent(context, TranslationDownloadService::class.java))
        TranslationDownloadService.clearNotification(context)
        context
            .getSharedPreferences("download_session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    suspend fun copyToStaging(
        context: Context,
        profile: GameProfile,
        asset: ReleaseAsset,
        destination: File,
        progress: DownloadProgressSink,
    ): BundleSourceResult {
        val terminal =
            state
                .onEach { snapshot ->
                    if (
                        snapshot.profile?.bundleProfile == profile.bundleProfile &&
                            snapshot.asset == asset
                    )
                        progress.onProgress(snapshot.bytes, asset.size)
                }
                .first { snapshot ->
                    snapshot.profile?.bundleProfile == profile.bundleProfile &&
                        snapshot.status in
                            setOf(
                                DownloadStatus.COMPLETE,
                                DownloadStatus.PAUSED,
                                DownloadStatus.CANCELLED,
                            )
                }
        if (terminal.status == DownloadStatus.CANCELLED)
            throw CancellationException("Download cancelled")
        terminal.failure?.let { throw BundleValidationException(it) }
        if (terminal.asset != asset)
            throw BundleValidationException(BundleFailureCode.RELEASE_CHANGED_DURING_DOWNLOAD)
        return withContext(Dispatchers.IO) {
            filesMutex.withLock {
                val source = ResumeCheckpoint(root(context), profile).partial
                if (!source.isFile || source.length() != asset.size)
                    throw BundleValidationException(BundleFailureCode.DOWNLOAD_SIZE_MISMATCH)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                source.inputStream().buffered().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > asset.size)
                                throw BundleValidationException(
                                    BundleFailureCode.DOWNLOAD_SIZE_MISMATCH
                                )
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                if (count != asset.size)
                    throw BundleValidationException(BundleFailureCode.DOWNLOAD_SIZE_MISMATCH)
                BundleSourceResult(
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
                    asset.name,
                )
            }
        }
    }

    suspend fun discardCompleted(context: Context, profile: GameProfile) =
        withContext(Dispatchers.IO) {
            filesMutex.withLock {
                if (state.value.active) return@withLock
                ResumeCheckpoint(root(context), profile).clear()
                if (state.value.profile?.bundleProfile == profile.bundleProfile) {
                    mutable.value = DownloadSnapshot()
                    context
                        .getSharedPreferences("download_session", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .apply()
                }
            }
        }
}
