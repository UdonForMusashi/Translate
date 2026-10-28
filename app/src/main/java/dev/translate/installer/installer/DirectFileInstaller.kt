package dev.translate.installer.installer

import android.content.Context
import android.os.Build
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.privileged.InstallEngineException
import dev.translate.installer.privileged.InstallError
import dev.translate.installer.privileged.InstallProgress
import dev.translate.installer.privileged.PrivilegedProfile
import dev.translate.installer.privileged.PrivilegedProfiles
import dev.translate.installer.privileged.TransactionalFileEngine
import dev.translate.installer.security.VerifiedBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

enum class InstallationAccessMode {
    LEGACY_DIRECT,
    SHIZUKU,
}

internal object LegacyDirectAccessPolicy {
    const val MAX_SUPPORTED_API = Build.VERSION_CODES.P

    fun modeFor(apiLevel: Int): InstallationAccessMode =
        if (apiLevel <= MAX_SUPPORTED_API) {
            InstallationAccessMode.LEGACY_DIRECT
        } else {
            InstallationAccessMode.SHIZUKU
        }
}

internal class DirectFileInstaller(context: Context) : FileInstaller {
    private val applicationContext = context.applicationContext
    private val engine = TransactionalFileEngine(rootResolver = ::resolveGameDirectory)

    override suspend fun probe(
        profile: GameProfile,
        requiredBytes: Long,
    ): InstallerCapabilities = withContext(Dispatchers.IO) {
        try {
            engine.probe(profile.toPrivilegedProfile(), requiredBytes)
            InstallerCapabilities(true, "AVAILABLE")
        } catch (exception: InstallEngineException) {
            InstallerCapabilities(false, reasonFor(exception.error))
        } catch (exception: Exception) {
            InstallerCapabilities(false, mapException(exception).reasonCode)
        }
    }

    override suspend fun install(
        profile: GameProfile,
        bundle: VerifiedBundle,
        progress: (InstallerProgress) -> Unit,
    ): InstallerOutcome = withContext(Dispatchers.IO) {
        try {
            validateBundleProfile(profile, bundle)
            val privilegedProfile = profile.toPrivilegedProfile()
            val records = bundle.manifest.files.map {
                dev.translate.installer.privileged.PrivilegedInstalledFile(
                    it.name,
                    it.size,
                    it.sha256,
                )
            }
            engine.probe(privilegedProfile, bundle.manifest.uncompressedSize)
            bundle.manifest.files.forEach { declared ->
                currentCoroutineContext().ensureActive()
                val root = bundle.extractedDirectory.canonicalFile
                val source = File(root, declared.archiveEntry).canonicalFile
                if (source.parentFile != File(root, "files").canonicalFile || !source.isFile) {
                    throw InstallerException("EXTRACTED_SOURCE_UNSAFE")
                }
                FileInputStream(source).use { input ->
                    engine.installFile(
                        privilegedProfile,
                        declared.name,
                        input,
                        declared.size,
                        declared.sha256,
                        progress.toPrivilegedProgress(),
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            engine.verifyInstalled(privilegedProfile, records)
            InstallerOutcome(installed = true)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: InstallerException) {
            throw exception
        } catch (exception: InstallEngineException) {
            throw InstallerException(reasonFor(exception.error), exception)
        } catch (exception: Exception) {
            throw mapException(exception)
        }
    }

    override suspend fun uninstall(
        profile: GameProfile,
        files: List<InstalledFileRecord>,
        progress: (InstallerProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            engine.uninstall(
                profile.toPrivilegedProfile(),
                files.map {
                    dev.translate.installer.privileged.PrivilegedInstalledFile(
                        it.name,
                        it.size,
                        it.sha256,
                    )
                },
                progress.toPrivilegedProgress(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: InstallEngineException) {
            throw InstallerException(reasonFor(exception.error), exception)
        } catch (exception: Exception) {
            throw mapException(exception)
        }
    }

    private fun resolveGameDirectory(profile: PrivilegedProfile): File {
        val ownFiles = applicationContext.getExternalFilesDir(null)
            ?: throw InstallEngineException(InstallError.TARGET_UNAVAILABLE)
        val ownPackageDirectory = ownFiles.parentFile
            ?: throw InstallEngineException(InstallError.TARGET_UNSAFE)
        val androidDataDirectory = ownPackageDirectory.parentFile
            ?: throw InstallEngineException(InstallError.TARGET_UNSAFE)
        val androidDirectory = androidDataDirectory.parentFile
            ?: throw InstallEngineException(InstallError.TARGET_UNSAFE)
        if (ownPackageDirectory.name != applicationContext.packageName ||
            androidDataDirectory.name != "data" || androidDirectory.name != "Android"
        ) {
            throw InstallEngineException(InstallError.TARGET_UNSAFE)
        }
        return File(
            androidDataDirectory,
            "${profile.packageName}/${profile.destinationRelativeDirectory.removeSuffix("/")}",
        )
    }

    private fun validateBundleProfile(profile: GameProfile, bundle: VerifiedBundle) {
        val manifest = bundle.manifest
        val bundleProfile = profile.bundleProfile
        if (manifest.profile != bundleProfile.name ||
            manifest.profileId != bundleProfile.profileId ||
            manifest.packageName != bundleProfile.packageName ||
            manifest.destinationDirectory != bundleProfile.destinationRelativeDirectory
        ) throw InstallerException("PROFILE_MISMATCH")
    }

    private fun ((InstallerProgress) -> Unit).toPrivilegedProgress() = InstallProgress {
            phase, fileName, processed, total ->
        val installerPhase = when (phase) {
            TransactionalFileEngine.PHASE_INSTALL -> InstallerPhase.INSTALL
            TransactionalFileEngine.PHASE_UNINSTALL -> InstallerPhase.UNINSTALL
            else -> return@InstallProgress
        }
        this(InstallerProgress(installerPhase, fileName, processed, total))
    }

    private fun GameProfile.toPrivilegedProfile(): PrivilegedProfile = when (this) {
        GameProfile.JP -> PrivilegedProfiles.JP
        GameProfile.NA -> PrivilegedProfiles.NA
        GameProfile.BA -> PrivilegedProfiles.BA
        GameProfile.BETTER_JP -> PrivilegedProfiles.BETTER_JP
        GameProfile.BETTER_NA -> PrivilegedProfiles.BETTER_NA
    }

    private fun mapException(exception: Throwable): InstallerException = when (exception) {
        is SecurityException -> InstallerException("LEGACY_STORAGE_PERMISSION_DENIED", exception)
        else -> InstallerException("INSTALLATION_IO_FAILURE", exception)
    }

    private fun reasonFor(error: InstallError): String = when (error) {
        InstallError.INVALID_CALLER -> "INVALID_CALLER"
        InstallError.INVALID_PROFILE -> "INVALID_PROFILE"
        InstallError.INVALID_FILE_NAME -> "INVALID_FILE_NAME"
        InstallError.TARGET_UNAVAILABLE -> "GAME_DIRECTORY_NOT_FOUND"
        InstallError.TARGET_UNSAFE -> "UNSAFE_GAME_DIRECTORY"
        InstallError.TARGET_NOT_WRITABLE -> "GAME_DIRECTORY_NOT_WRITABLE"
        InstallError.SOURCE_SIZE_MISMATCH -> "FILE_SIZE_MISMATCH"
        InstallError.SOURCE_HASH_MISMATCH -> "FILE_HASH_MISMATCH"
        InstallError.IO_FAILURE -> "INSTALLATION_IO_FAILURE"
        InstallError.INSUFFICIENT_STORAGE -> "INSUFFICIENT_STORAGE"
        InstallError.TARGET_FILE_MISSING -> "TARGET_FILE_MISSING"
        InstallError.TARGET_CHANGED -> "TARGET_CHANGED"
        InstallError.RECEIPT_INVALID -> "RECEIPT_INVALID"
    }
}
