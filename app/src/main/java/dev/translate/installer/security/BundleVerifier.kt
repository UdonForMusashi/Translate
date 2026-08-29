package dev.translate.installer.security

import dev.translate.installer.domain.GameProfile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

data class VerifiedBundle(
    val manifest: LocalBundleManifest,
    val archiveFile: File,
    val extractedDirectory: File,
)

fun interface ExtractionProgressSink {
    fun onProgress(path: String, processed: Long, total: Long)
}

enum class VerificationNotice {
    SIGNATURE_STARTED,
    SIGNATURE_SUCCEEDED,
    INSPECTION_STARTED,
    INSPECTION_SUCCEEDED,
    EXTRACTION_STARTED,
    EXTRACTION_SUCCEEDED,
}

fun interface VerificationNoticeSink {
    fun onNotice(notice: VerificationNotice)
}

class BundleVerifier(
    private val signatureVerifier: ManifestSignatureVerifier,
    private val parser: StrictJsonParsers = StrictJsonParsers(),
    private val policy: ManifestPolicy = ManifestPolicy(),
    private val limits: ImportLimits = ImportLimits(),
) {
    fun verifyAndExtract(
        archiveFile: File,
        extractionDirectory: File,
        selectedProfile: GameProfile,
        progressSink: ExtractionProgressSink = ExtractionProgressSink { _, _, _ -> },
        noticeSink: VerificationNoticeSink = VerificationNoticeSink { },
    ): VerifiedBundle {
        if (!archiveFile.isFile) fail(BundleFailureCode.SOURCE_UNAVAILABLE)
        if (archiveFile.length() > limits.maxArchiveBytes) {
            fail(BundleFailureCode.ARCHIVE_TOO_LARGE)
        }
        preflightZipCentralDirectory(archiveFile, limits)
        if (extractionDirectory.exists() || !extractionDirectory.mkdirs()) {
            fail(BundleFailureCode.IO_ERROR)
        }

        try {
            ZipFile.builder().setFile(archiveFile).get().use { zip ->
                val entries = enumerate(zip)
                val manifestEntry = requiredEntry(entries, "manifest.json")
                val signatureEntry = requiredEntry(entries, "manifest.sig")
                validateMetadataEntry(zip, manifestEntry, limits.maxManifestBytes)
                validateMetadataEntry(zip, signatureEntry, limits.maxSignatureEnvelopeBytes)

                val manifestBytes = readLimited(zip, manifestEntry, limits.maxManifestBytes)
                val envelopeBytes = readLimited(
                    zip,
                    signatureEntry,
                    limits.maxSignatureEnvelopeBytes,
                )
                noticeSink.onNotice(VerificationNotice.SIGNATURE_STARTED)
                val envelope = parser.parseSignatureEnvelope(envelopeBytes)
                signatureVerifier.verify(manifestBytes, envelope)
                noticeSink.onNotice(VerificationNotice.SIGNATURE_SUCCEEDED)

                noticeSink.onNotice(VerificationNotice.INSPECTION_STARTED)
                val manifest = parser.parseManifest(manifestBytes)
                policy.validate(
                    manifest = manifest,
                    envelope = envelope,
                    selectedProfile = selectedProfile,
                )

                validateExactStructure(zip, entries, manifest)
                ensureSufficientStorage(
                    directory = extractionDirectory,
                    requiredBytes = manifest.uncompressedSize,
                    limits = limits,
                )
                noticeSink.onNotice(VerificationNotice.INSPECTION_SUCCEEDED)
                noticeSink.onNotice(VerificationNotice.EXTRACTION_STARTED)
                extractDeclaredFiles(zip, entries, manifest, extractionDirectory, progressSink)
                noticeSink.onNotice(VerificationNotice.EXTRACTION_SUCCEEDED)

                return VerifiedBundle(
                    manifest = manifest,
                    archiveFile = archiveFile,
                    extractedDirectory = extractionDirectory,
                )
            }
        } catch (exception: BundleValidationException) {
            extractionDirectory.deleteRecursively()
            throw exception
        } catch (exception: Exception) {
            extractionDirectory.deleteRecursively()
            fail(BundleFailureCode.ARCHIVE_INVALID, exception)
        }
    }

    private fun enumerate(zip: ZipFile): Map<String, ZipArchiveEntry> {
        val entries = linkedMapOf<String, ZipArchiveEntry>()
        val enumeration = zip.entries
        while (enumeration.hasMoreElements()) {
            if (entries.size >= limits.maxArchiveEntries) {
                fail(BundleFailureCode.TOO_MANY_ENTRIES)
            }
            val entry = enumeration.nextElement()
            val name = entry.name
            if (name.isEmpty() ||
                name.length > limits.maxArchiveEntryNameLength ||
                name.indexOf('\u0000') >= 0 ||
                !entry.comment.isNullOrEmpty() ||
                (entry.extra?.size ?: 0) > limits.maxArchiveEntryExtraBytes
            ) {
                fail(BundleFailureCode.UNSAFE_ENTRY)
            }
            if (entries.put(name, entry) != null) {
                fail(BundleFailureCode.DUPLICATE_ENTRY)
            }
        }
        return entries
    }

    private fun requiredEntry(
        entries: Map<String, ZipArchiveEntry>,
        name: String,
    ): ZipArchiveEntry = entries[name] ?: fail(BundleFailureCode.REQUIRED_ENTRY_MISSING)

    private fun validateMetadataEntry(
        zip: ZipFile,
        entry: ZipArchiveEntry,
        maxBytes: Long,
    ) {
        if (entry.isDirectory || entry.isUnixSymlink || !zip.canReadEntryData(entry)) {
            fail(BundleFailureCode.UNSAFE_ENTRY)
        }
        if (entry.size < 0 || entry.size > maxBytes) {
            fail(
                if (entry.name == "manifest.json") BundleFailureCode.MANIFEST_TOO_LARGE
                else BundleFailureCode.SIGNATURE_ENVELOPE_TOO_LARGE,
            )
        }
        validateCompressionMethod(entry)
    }

    private fun validateExactStructure(
        zip: ZipFile,
        entries: Map<String, ZipArchiveEntry>,
        manifest: LocalBundleManifest,
    ) {
        val expectedFiles = manifest.files.associateBy { it.archiveEntry }
        val allowedNames = expectedFiles.keys + setOf("manifest.json", "manifest.sig", "files/")

        entries.forEach { (name, entry) ->
            if (name !in allowedNames) fail(BundleFailureCode.UNEXPECTED_ENTRY)
            if (entry.isUnixSymlink) fail(BundleFailureCode.UNSAFE_ENTRY)
            if (name == "files/") {
                if (!entry.isDirectory) fail(BundleFailureCode.UNSAFE_ENTRY)
                return@forEach
            }
            if (entry.isDirectory || !zip.canReadEntryData(entry)) {
                fail(BundleFailureCode.UNSAFE_ENTRY)
            }
            validateCompressionMethod(entry)

            val declared = expectedFiles[name] ?: return@forEach
            if (entry.size != declared.size || entry.size > limits.maxSingleFileBytes) {
                fail(BundleFailureCode.FILE_SIZE_MISMATCH)
            }
            val compressedSize = entry.compressedSize
            if (compressedSize < 0 || compressedSize > limits.maxArchiveBytes) {
                fail(BundleFailureCode.ARCHIVE_INVALID)
            }
            if (entry.size >= limits.compressionRatioCheckThreshold &&
                (compressedSize == 0L ||
                    entry.size > compressedSize * limits.maxCompressionRatio)
            ) {
                fail(BundleFailureCode.COMPRESSION_RATIO_EXCEEDED)
            }
        }

        if (!entries.keys.containsAll(expectedFiles.keys)) {
            fail(BundleFailureCode.REQUIRED_ENTRY_MISSING)
        }
    }

    private fun validateCompressionMethod(entry: ZipArchiveEntry) {
        if (entry.method != ZipMethod.STORED.code && entry.method != ZipMethod.DEFLATED.code) {
            fail(BundleFailureCode.UNSUPPORTED_COMPRESSION)
        }
    }

    private fun extractDeclaredFiles(
        zip: ZipFile,
        entries: Map<String, ZipArchiveEntry>,
        manifest: LocalBundleManifest,
        extractionDirectory: File,
        progressSink: ExtractionProgressSink,
    ) {
        val canonicalRoot = extractionDirectory.canonicalFile
        manifest.files.forEach { declared ->
            val entry = requiredEntry(entries, declared.archiveEntry)
            val destination = File(canonicalRoot, declared.archiveEntry).canonicalFile
            if (!destination.path.startsWith(canonicalRoot.path + File.separator)) {
                fail(BundleFailureCode.UNSAFE_ENTRY)
            }
            val parent = destination.parentFile ?: fail(BundleFailureCode.IO_ERROR)
            if (!parent.exists() && !parent.mkdirs()) fail(BundleFailureCode.IO_ERROR)
            val partial = File(parent, destination.name + ".partial")

            val digest = MessageDigest.getInstance("SHA-256")
            var processed = 0L
            try {
                BufferedInputStream(zip.getInputStream(entry)).use { input ->
                    FileOutputStream(partial).use { fileOutput ->
                        val output = BufferedOutputStream(fileOutput)
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            processed += read
                            if (processed > declared.size ||
                                processed > limits.maxSingleFileBytes
                            ) {
                                fail(BundleFailureCode.FILE_SIZE_MISMATCH)
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            progressSink.onProgress(
                                declared.archiveEntry,
                                processed,
                                declared.size,
                            )
                        }
                        output.flush()
                        fileOutput.fd.sync()
                    }
                }
                if (processed != declared.size) fail(BundleFailureCode.FILE_SIZE_MISMATCH)
                if (digest.digest().toHex() != declared.sha256) {
                    fail(BundleFailureCode.FILE_HASH_MISMATCH)
                }
                if (!partial.renameTo(destination)) fail(BundleFailureCode.IO_ERROR)
            } finally {
                if (partial.exists()) partial.delete()
            }
        }
    }

    private fun readLimited(
        zip: ZipFile,
        entry: ZipArchiveEntry,
        maxBytes: Long,
    ): ByteArray = zip.getInputStream(entry).use { input ->
        input.readBounded(maxBytes)
    }
}

private fun InputStream.readBounded(maxBytes: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) fail(BundleFailureCode.ARCHIVE_INVALID)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
