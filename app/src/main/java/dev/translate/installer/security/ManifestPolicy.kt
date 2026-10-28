package dev.translate.installer.security

import dev.translate.installer.domain.GameProfile
import dev.translate.installer.domain.decodeTechnical
import java.time.Instant
import java.time.format.DateTimeParseException

class ManifestPolicy(
    private val limits: ImportLimits = ImportLimits(),
) {
    fun validate(
        manifest: LocalBundleManifest,
        envelope: SignatureEnvelope,
        selectedProfile: GameProfile,
    ) {
        val bundleProfile = selectedProfile.bundleProfile
        if (manifest.schemaVersion != SCHEMA_VERSION || envelope.schemaVersion != SCHEMA_VERSION) invalid()
        if (envelope.algorithm != SUPPORTED_ALGORITHM) invalid()
        if (manifest.keyId != envelope.keyId) invalid()
        if (manifest.keyId != RELEASE_KEY_ID || !SAFE_VERSION.matches(manifest.version)) invalid()
        try {
            Instant.parse(manifest.createdAt)
        } catch (_: DateTimeParseException) {
            invalid()
        }

        if (manifest.profile != bundleProfile.name ||
            manifest.profileId != bundleProfile.profileId
        ) {
            fail(BundleFailureCode.PROFILE_MISMATCH)
        }
        if (manifest.packageName != bundleProfile.packageName) {
            fail(BundleFailureCode.PACKAGE_MISMATCH)
        }
        if (manifest.destinationDirectory != bundleProfile.destinationRelativeDirectory) {
            fail(BundleFailureCode.UNSAFE_ENTRY)
        }

        if (manifest.files.isEmpty() || manifest.files.size > limits.maxArchiveEntries - 2) {
            fail(BundleFailureCode.FILE_COUNT_MISMATCH)
        }
        if (manifest.fileCount != manifest.files.size.toLong()) {
            fail(BundleFailureCode.FILE_COUNT_MISMATCH)
        }

        val names = mutableSetOf<String>()
        val archiveEntries = mutableSetOf<String>()
        var totalSize = 0L

        manifest.files.forEach { file ->
            if (file.size > limits.maxSingleFileBytes) {
                fail(BundleFailureCode.FILE_SIZE_MISMATCH)
            }
            totalSize = addWithoutOverflow(totalSize, file.size)
            if (totalSize > limits.maxUncompressedBytes) {
                fail(BundleFailureCode.UNCOMPRESSED_SIZE_MISMATCH)
            }
            if (!SHA_256.matches(file.sha256)) invalid()
            if (!selectedProfile.contentPolicy.isAllowedFileName(file.name) ||
                file.archiveEntry != "files/${file.name}"
            ) {
                fail(BundleFailureCode.UNSAFE_ENTRY)
            }
            if (!names.add(file.name) || !archiveEntries.add(file.archiveEntry)) {
                fail(BundleFailureCode.DUPLICATE_ENTRY)
            }
        }

        if (!selectedProfile.contentPolicy.isValidFileNameSet(names)) {
            fail(BundleFailureCode.FILE_COUNT_MISMATCH)
        }
        if (manifest.uncompressedSize != totalSize) {
            fail(BundleFailureCode.UNCOMPRESSED_SIZE_MISMATCH)
        }
    }

    private fun addWithoutOverflow(left: Long, right: Long): Long {
        if (right < 0 || left > Long.MAX_VALUE - right) {
            fail(BundleFailureCode.UNCOMPRESSED_SIZE_MISMATCH)
        }
        return left + right
    }

    private fun invalid(): Nothing = fail(BundleFailureCode.MANIFEST_INVALID)

    companion object {
        const val SUPPORTED_ALGORITHM = "SHA256withECDSA"
        const val SCHEMA_VERSION = 2L
        val RELEASE_KEY_ID = decodeTechnical("dHJhbnNsYXRlZmdvLW1haW4=")
        private val SAFE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
