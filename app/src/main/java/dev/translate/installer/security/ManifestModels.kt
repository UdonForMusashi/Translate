package dev.translate.installer.security

data class LocalBundleManifest(
    val schemaVersion: Long,
    val keyId: String,
    val profile: String,
    val profileId: String,
    val packageName: String,
    val destinationDirectory: String,
    val version: String,
    val createdAt: String,
    val fileCount: Long,
    val uncompressedSize: Long,
    val files: List<ManifestFile>,
)

data class ManifestFile(
    val name: String,
    val archiveEntry: String,
    val size: Long,
    val sha256: String,
)

data class SignatureEnvelope(
    val schemaVersion: Long,
    val keyId: String,
    val algorithm: String,
    val signature: ByteArray,
)
