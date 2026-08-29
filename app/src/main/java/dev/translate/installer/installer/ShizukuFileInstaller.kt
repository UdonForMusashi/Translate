package dev.translate.installer.installer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import dev.translate.installer.BuildConfig
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.privileged.IInstallProgressCallback
import dev.translate.installer.privileged.IPrivilegedInstallerService
import dev.translate.installer.privileged.PrivilegedInstallerService
import dev.translate.installer.privileged.TransactionalFileEngine
import dev.translate.installer.security.VerifiedBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShizukuFileInstaller(context: Context) : FileInstaller {
    private val applicationContext = context.applicationContext

    override suspend fun probe(
        profile: GameProfile,
        requiredBytes: Long,
    ): InstallerCapabilities = withService { service ->
        val code = service.probe(profile.toPrivilegedCode(), requiredBytes)
        InstallerCapabilities(code == 0, reasonFor(code))
    }

    override suspend fun install(
        profile: GameProfile,
        bundle: VerifiedBundle,
        progress: (InstallerProgress) -> Unit,
    ): InstallerOutcome = withService { service ->
        validateBundleProfile(profile, bundle)
        val files = bundle.manifest.files
        val names = files.map { it.name }
        val callback = progressCallback(names.toSet(), progress)
        requireSuccess(service.probe(
            profile.toPrivilegedCode(),
            bundle.manifest.uncompressedSize,
        ))
        files.forEach { declared ->
            currentCoroutineContext().ensureActive()
            val root = bundle.extractedDirectory.canonicalFile
            val source = File(root, declared.archiveEntry).canonicalFile
            if (source.parentFile != File(root, "files").canonicalFile || !source.isFile) {
                throw InstallerException("EXTRACTED_SOURCE_UNSAFE")
            }
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                requireSuccess(service.installFile(
                    profile.toPrivilegedCode(),
                    declared.name,
                    descriptor,
                    declared.size,
                    declared.sha256,
                    callback,
                ))
            }
        }
        currentCoroutineContext().ensureActive()
        requireSuccess(service.verifyInstalled(
            profile.toPrivilegedCode(),
            names.toTypedArray(),
            files.map { it.size }.toLongArray(),
            files.map { it.sha256 }.toTypedArray(),
        ))
        InstallerOutcome(installed = true)
    }

    override suspend fun uninstall(
        profile: GameProfile,
        files: List<InstalledFileRecord>,
        progress: (InstallerProgress) -> Unit,
    ) = withService { service ->
        validateReceiptFiles(files)
        requireSuccess(service.uninstall(
            profile.toPrivilegedCode(),
            files.map { it.name }.toTypedArray(),
            files.map { it.size }.toLongArray(),
            files.map { it.sha256 }.toTypedArray(),
            progressCallback(files.map { it.name }.toSet(), progress),
        ))
    }

    private suspend fun <T> withService(operation: suspend (IPrivilegedInstallerService) -> T): T =
        withContext(Dispatchers.IO) {
            val binding = bind()
            try {
                operation(binding.service)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: InstallerException) {
                throw exception
            } catch (exception: Exception) {
                throw mapException(exception)
            } finally {
                try {
                    Shizuku.unbindUserService(binding.args, binding.connection, true)
                } catch (_: RuntimeException) {
                }
            }
        }

    private suspend fun bind(): Binding = withTimeout(BIND_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val args = Shizuku.UserServiceArgs(
                ComponentName(applicationContext, PrivilegedInstallerService::class.java),
            ).processNameSuffix("translation_installer")
                .tag(USER_SERVICE_TAG)
                .version(USER_SERVICE_VERSION)
                .daemon(false)
                .debuggable(BuildConfig.DEBUG)
            lateinit var connection: ServiceConnection
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = binder?.let(IPrivilegedInstallerService.Stub::asInterface)
                    if (service == null) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(InstallerException("SERVICE_BIND_FAILED"))
                        }
                    } else if (continuation.isActive) {
                        continuation.resume(Binding(args, connection, service))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(InstallerException("SERVICE_DISCONNECTED"))
                    }
                }
            }
            continuation.invokeOnCancellation {
                try {
                    Shizuku.unbindUserService(args, connection, true)
                } catch (_: RuntimeException) {
                }
            }
            try {
                Shizuku.bindUserService(args, connection)
            } catch (exception: Exception) {
                if (continuation.isActive) continuation.resumeWithException(mapException(exception))
            }
        }
    }

    private fun progressCallback(
        allowedNames: Set<String>,
        progress: (InstallerProgress) -> Unit,
    ) = object : IInstallProgressCallback.Stub() {
        override fun onProgress(phase: Int, fileName: String?, processed: Long, total: Long) {
            val safeName = fileName ?: return
            if (safeName !in allowedNames || !isSafeFileName(safeName) ||
                processed < 0 || total < 0 || processed > total) return
            val mapped = when (phase) {
                TransactionalFileEngine.PHASE_INSTALL -> InstallerPhase.INSTALL
                TransactionalFileEngine.PHASE_UNINSTALL -> InstallerPhase.UNINSTALL
                else -> return
            }
            progress(InstallerProgress(mapped, safeName, processed, total))
        }
    }

    private fun validateBundleProfile(profile: GameProfile, bundle: VerifiedBundle) {
        val manifest = bundle.manifest
        if (manifest.profile != profile.name || manifest.profileId != profile.profileId ||
            manifest.packageName != profile.packageName ||
            manifest.destinationDirectory != profile.destinationRelativeDirectory
        ) throw InstallerException("PROFILE_MISMATCH")
    }

    private fun validateReceiptFiles(files: List<InstalledFileRecord>) {
        val recordsByName = files.groupBy { it.name }
        if (files.isEmpty() || files.size > MAX_FILES ||
            files.toSet().size != files.size ||
            recordsByName.values.any { it.size > MAX_HASHES_PER_NAME } ||
            TransactionalFileEngine.SPECIAL_FILE !in recordsByName ||
            files.none { BIN_NAME.matches(it.name) }
        ) throw InstallerException("RECEIPT_INVALID")
        files.forEach { file ->
            if (!isSafeFileName(file.name) || file.size < 0 ||
                file.size > MAX_FILE_BYTES || !SHA_256.matches(file.sha256)
            ) throw InstallerException("RECEIPT_INVALID")
        }
    }

    private fun GameProfile.toPrivilegedCode() = when (this) {
        GameProfile.JP -> 1
        GameProfile.NA -> 2
    }

    private fun mapException(exception: Throwable): InstallerException {
        if (exception is InstallerException) return exception
        val reason = when (exception) {
            is SecurityException -> "SHIZUKU_PERMISSION_DENIED"
            is RemoteException -> "SERVICE_DISCONNECTED"
            else -> "INSTALLATION_IO_FAILURE"
        }
        return InstallerException(reason, exception)
    }

    private fun reasonFor(code: Int) = SERVICE_REASONS[code] ?: "UNKNOWN_SERVICE_ERROR"
    private fun requireSuccess(code: Int) {
        if (code != 0) throw InstallerException(reasonFor(code))
    }

    private fun isSafeFileName(name: String) =
        name == TransactionalFileEngine.SPECIAL_FILE || BIN_NAME.matches(name)

    private data class Binding(
        val args: Shizuku.UserServiceArgs,
        val connection: ServiceConnection,
        val service: IPrivilegedInstallerService,
    )

    private companion object {
        const val USER_SERVICE_TAG = "translation-installer-v2"
        const val USER_SERVICE_VERSION = 2
        const val BIND_TIMEOUT_MS = 15_000L
        const val MAX_FILES = 4_094
        const val MAX_HASHES_PER_NAME = 2
        const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
        val BIN_NAME = Regex("[A-Za-z0-9_-]{1,128}\\.bin")
        val SHA_256 = Regex("[0-9a-f]{64}")
        val SERVICE_REASONS = mapOf(
            0 to "AVAILABLE",
            1 to "INVALID_CALLER",
            2 to "INVALID_PROFILE",
            3 to "INVALID_FILE_NAME",
            4 to "GAME_DIRECTORY_NOT_FOUND",
            5 to "UNSAFE_GAME_DIRECTORY",
            6 to "GAME_DIRECTORY_NOT_WRITABLE",
            7 to "FILE_SIZE_MISMATCH",
            8 to "FILE_HASH_MISMATCH",
            9 to "INSTALLATION_IO_FAILURE",
            10 to "INSUFFICIENT_STORAGE",
            11 to "TARGET_FILE_MISSING",
            12 to "TARGET_CHANGED",
            13 to "RECEIPT_INVALID",
        )
    }
}
