package dev.translate.installer.security

data class ImportLimits(
    val maxArchiveBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxManifestBytes: Long = 1L * 1024 * 1024,
    val maxSignatureEnvelopeBytes: Long = 64L * 1024,
    val maxArchiveEntries: Int = 4_096,
    val maxCentralDirectoryBytes: Long = 16L * 1024 * 1024,
    val maxArchiveEntryNameLength: Int = 256,
    val maxArchiveEntryExtraBytes: Int = 4 * 1024,
    val maxSingleFileBytes: Long = 1L * 1024 * 1024 * 1024,
    val maxUncompressedBytes: Long = 4L * 1024 * 1024 * 1024,
    val compressionRatioCheckThreshold: Long = 1L * 1024 * 1024,
    val maxCompressionRatio: Long = 200,
    val minimumFreeBytes: Long = 256L * 1024 * 1024,
    val storageCheckIntervalBytes: Long = 16L * 1024 * 1024,
)
