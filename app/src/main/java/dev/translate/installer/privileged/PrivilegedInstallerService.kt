package dev.translate.installer.privileged

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.File
import java.io.FileInputStream

@Keep
class PrivilegedInstallerService : IPrivilegedInstallerService.Stub {
    private var expectedCallerUid: Int = INVALID_UID
    private var androidUserId: Int = 0
    private var engine: TransactionalFileEngine = createEngine()

    constructor() : super() {
    }

    @Keep
    constructor(context: Context) : this() {
        expectedCallerUid = context.applicationInfo.uid
        androidUserId = expectedCallerUid / PER_USER_RANGE
        engine = createEngine()
    }

    override fun probe(profileCode: Int, requiredBytes: Long): Int {
        if (!isCallerValid()) return InstallError.INVALID_CALLER.serviceCode
        return runCatching {
            engine.probe(PrivilegedProfiles.fromCode(profileCode), requiredBytes)
            PROBE_AVAILABLE
        }.getOrElse(::errorCode)
    }

    override fun installFile(
        profileCode: Int,
        fileName: String?,
        source: ParcelFileDescriptor?,
        size: Long,
        sha256: String?,
        callback: IInstallProgressCallback?,
    ): Int {
        if (!isCallerValid()) return InstallError.INVALID_CALLER.serviceCode
        val descriptor = source ?: return InstallError.RECEIPT_INVALID.serviceCode
        return remoteResult {
            descriptor.use {
                FileInputStream(it.fileDescriptor).use { input ->
                    engine.installFile(
                        PrivilegedProfiles.fromCode(profileCode),
                        fileName.requireArgument(InstallError.INVALID_FILE_NAME),
                        input,
                        size,
                        sha256.requireArgument(InstallError.RECEIPT_INVALID),
                        callback.asProgress(),
                    )
                }
            }
        }
    }

    override fun verifyInstalled(
        profileCode: Int,
        fileNames: Array<out String>?,
        sizes: LongArray?,
        sha256: Array<out String>?,
    ): Int {
        if (!isCallerValid()) return InstallError.INVALID_CALLER.serviceCode
        return remoteResult {
            engine.verifyInstalled(
                PrivilegedProfiles.fromCode(profileCode),
                records(fileNames, sizes, sha256),
            )
        }
    }

    override fun uninstall(
        profileCode: Int,
        fileNames: Array<out String>?,
        sizes: LongArray?,
        sha256: Array<out String>?,
        callback: IInstallProgressCallback?,
    ): Int {
        if (!isCallerValid()) return InstallError.INVALID_CALLER.serviceCode
        return remoteResult {
            engine.uninstall(
                PrivilegedProfiles.fromCode(profileCode),
                records(fileNames, sizes, sha256),
                callback.asProgress(),
            )
        }
    }

    override fun destroy() {
        if (isCallerValid()) System.exit(0)
    }

    private fun createEngine() = TransactionalFileEngine(
        rootResolver = { profile ->
            File(
                "/storage/emulated/$androidUserId/Android/data/${profile.packageName}/files/data/d713",
            )
        },
    )

    private fun isCallerValid(): Boolean =
        expectedCallerUid >= 0 && Binder.getCallingUid() == expectedCallerUid

    private inline fun remoteResult(block: () -> Unit): Int = try {
        block()
        0
    } catch (exception: InstallEngineException) {
        exception.error.serviceCode
    } catch (_: Exception) {
        InstallError.IO_FAILURE.serviceCode
    }

    private fun errorCode(throwable: Throwable): Int = when (throwable) {
        is InstallEngineException -> throwable.error.serviceCode
        else -> InstallError.IO_FAILURE.serviceCode
    }

    private fun IInstallProgressCallback?.asProgress() = InstallProgress {
            phase, fileName, processed, total ->
        try {
            this?.onProgress(phase, fileName, processed, total)
        } catch (_: Exception) {
        }
    }

    private fun String?.requireArgument(error: InstallError): String =
        this ?: throw InstallEngineException(error)

    private fun records(
        names: Array<out String>?,
        sizes: LongArray?,
        hashes: Array<out String>?,
    ): List<PrivilegedInstalledFile> {
        if (names == null || sizes == null || hashes == null ||
            names.size != sizes.size || names.size != hashes.size
        ) {
            throw InstallEngineException(InstallError.RECEIPT_INVALID)
        }
        return names.indices.map { index ->
            PrivilegedInstalledFile(names[index], sizes[index], hashes[index])
        }
    }

    private companion object {
        const val PROBE_AVAILABLE = 0
        const val INVALID_UID = -1
        const val PER_USER_RANGE = 100_000
    }
}
