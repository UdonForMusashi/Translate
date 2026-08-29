package dev.translate.installer.installer

import dev.translate.installer.domain.GameProfile
import dev.translate.installer.security.VerifiedBundle

data class InstallerCapabilities(val available: Boolean, val reasonCode: String)
data class InstallerOutcome(val installed: Boolean)

data class InstalledFileRecord(
    val name: String,
    val size: Long,
    val sha256: String,
)

data class InstallerProgress(
    val phase: InstallerPhase,
    val fileName: String,
    val processed: Long,
    val total: Long,
)

enum class InstallerPhase { INSTALL, UNINSTALL }

class InstallerException(val reasonCode: String, cause: Throwable? = null) :
    Exception(reasonCode, cause)

interface FileInstaller {
    suspend fun probe(profile: GameProfile, requiredBytes: Long): InstallerCapabilities
    suspend fun install(
        profile: GameProfile,
        bundle: VerifiedBundle,
        progress: (InstallerProgress) -> Unit,
    ): InstallerOutcome
    suspend fun uninstall(
        profile: GameProfile,
        files: List<InstalledFileRecord>,
        progress: (InstallerProgress) -> Unit,
    )
}
