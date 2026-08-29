package dev.translate.installer.data.importer

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.translate.installer.audit.MessageCode
import dev.translate.installer.audit.OperationPhase
import dev.translate.installer.audit.OperationStatus
import dev.translate.installer.audit.TransactionEventEmitter
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.security.BundleFailureCode
import dev.translate.installer.security.BundleValidationException
import dev.translate.installer.security.BundleVerifier
import dev.translate.installer.security.ExtractionProgressSink
import dev.translate.installer.security.ImportLimits
import dev.translate.installer.security.ensureSufficientStorage
import dev.translate.installer.security.VerificationNotice
import dev.translate.installer.security.VerificationNoticeSink
import dev.translate.installer.security.VerifiedBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class ImportedLocalBundle(
    val verifiedBundle: VerifiedBundle,
    val archiveSha256: String,
    val sourceDisplayName: String,
)

class LocalBundleImporter(
    context: Context,
    private val verifier: BundleVerifier,
    private val limits: ImportLimits = ImportLimits(),
) {
    private val resolver: ContentResolver = context.contentResolver
    private val importsRoot = File(context.filesDir, "local-bundles")
    private val storageMutex = Mutex()

    suspend fun import(
        source: Uri,
        transactionId: String,
        selectedProfile: GameProfile,
        events: TransactionEventEmitter,
    ): ImportedLocalBundle = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            importLocked(source, transactionId, selectedProfile, events)
        }
    }

    private suspend fun importLocked(
        source: Uri,
        transactionId: String,
        selectedProfile: GameProfile,
        events: TransactionEventEmitter,
    ): ImportedLocalBundle {
        if (source.scheme != ContentResolver.SCHEME_CONTENT) {
            throw BundleValidationException(BundleFailureCode.SOURCE_UNAVAILABLE)
        }
        if (!TRANSACTION_ID.matches(transactionId)) ioFailure()
        if (!importsRoot.exists() && !importsRoot.mkdirs()) ioFailure()
        if (!importsRoot.isDirectory) ioFailure()
        clearStaleTransactions()

        val transactionRoot = File(importsRoot, transactionId)
        if (transactionRoot.exists() || !transactionRoot.mkdir()) ioFailure()

        val displayName = querySafeDisplayName(source)
        val declaredSourceSize = querySizeForProgress(source)
        val partial = File(transactionRoot, "bundle.zip.partial")
        val archive = File(transactionRoot, "bundle.zip")
        val extracted = File(transactionRoot, "extracted")

        return try {
            ensureSufficientStorage(
                directory = transactionRoot,
                requiredBytes = declaredSourceSize ?: 0,
                limits = limits,
            )
            events.emit(
                phase = OperationPhase.IMPORT,
                status = OperationStatus.STARTED,
                messageCode = MessageCode.SOURCE_COPY_STARTED,
                fileRelativePath = displayName,
                bytesTotal = declaredSourceSize,
            )
            val archiveHash = copySource(
                source = source,
                destination = partial,
                displayName = displayName,
                declaredSize = declaredSourceSize,
                events = events,
            )
            if (!partial.renameTo(archive)) ioFailure()
            events.emit(
                phase = OperationPhase.IMPORT,
                status = OperationStatus.SUCCEEDED,
                messageCode = MessageCode.SOURCE_COPY_SUCCEEDED,
                fileRelativePath = displayName,
                bytesProcessed = archive.length(),
                bytesTotal = archive.length(),
            )

            var lastProgressAtNanos = 0L
            val verified = verifier.verifyAndExtract(
                archiveFile = archive,
                extractionDirectory = extracted,
                selectedProfile = selectedProfile,
                noticeSink = VerificationNoticeSink { notice ->
                    notice.emitTo(events)
                },
                progressSink = ExtractionProgressSink { path, processed, total ->
                    val now = System.nanoTime()
                    if (processed == total || now - lastProgressAtNanos >= PROGRESS_INTERVAL_NANOS) {
                        lastProgressAtNanos = now
                        events.emit(
                            phase = OperationPhase.EXTRACT,
                            status = OperationStatus.PROGRESS,
                            messageCode = MessageCode.FILE_EXTRACTION_PROGRESS,
                            fileRelativePath = path,
                            bytesProcessed = processed,
                            bytesTotal = total,
                        )
                    }
                },
            )
            events.emit(
                phase = OperationPhase.EXTRACT,
                status = OperationStatus.SUCCEEDED,
                messageCode = MessageCode.BUNDLE_READY,
            )
            ImportedLocalBundle(
                verifiedBundle = verified,
                archiveSha256 = archiveHash,
                sourceDisplayName = displayName,
            )
        } catch (exception: CancellationException) {
            transactionRoot.deleteRecursively()
            throw exception
        } catch (exception: Exception) {
            transactionRoot.deleteRecursively()
            when (exception) {
                is BundleValidationException -> throw exception
                else -> throw BundleValidationException(BundleFailureCode.IO_ERROR, exception)
            }
        }
    }

    suspend fun clearPrivateStaging() = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            if (!importsRoot.exists()) return@withLock
            if (!importsRoot.isDirectory) ioFailure()
            clearStaleTransactions()
        }
    }

    private suspend fun copySource(
        source: Uri,
        destination: File,
        displayName: String,
        declaredSize: Long?,
        events: TransactionEventEmitter,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var processed = 0L
        var lastProgressAtNanos = 0L
        var lastStorageCheckAt = 0L
        val sourceStream = try {
            resolver.openInputStream(source)
        } catch (exception: SecurityException) {
            throw BundleValidationException(
                BundleFailureCode.SOURCE_PERMISSION_DENIED,
                exception,
            )
        } catch (exception: IOException) {
            throw BundleValidationException(BundleFailureCode.SOURCE_READ_FAILED, exception)
        } catch (exception: RuntimeException) {
            throw BundleValidationException(BundleFailureCode.SOURCE_READ_FAILED, exception)
        } ?: throw BundleValidationException(BundleFailureCode.SOURCE_UNAVAILABLE)

        BufferedInputStream(sourceStream).use { input ->
            FileOutputStream(destination).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = try {
                        input.read(buffer)
                    } catch (exception: SecurityException) {
                        throw BundleValidationException(
                            BundleFailureCode.SOURCE_PERMISSION_DENIED,
                            exception,
                        )
                    } catch (exception: IOException) {
                        throw BundleValidationException(
                            BundleFailureCode.SOURCE_READ_FAILED,
                            exception,
                        )
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    processed += read
                    if (processed > limits.maxArchiveBytes) {
                        throw BundleValidationException(BundleFailureCode.ARCHIVE_TOO_LARGE)
                    }
                    if (processed - lastStorageCheckAt >= limits.storageCheckIntervalBytes) {
                        ensureSufficientStorage(
                            directory = destination.parentFile ?: destination,
                            requiredBytes = 0,
                            limits = limits,
                        )
                        lastStorageCheckAt = processed
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)

                    val now = System.nanoTime()
                    if (now - lastProgressAtNanos >= PROGRESS_INTERVAL_NANOS) {
                        lastProgressAtNanos = now
                        events.emit(
                            phase = OperationPhase.IMPORT,
                            status = OperationStatus.PROGRESS,
                            messageCode = MessageCode.SOURCE_COPY_PROGRESS,
                            fileRelativePath = displayName,
                            bytesProcessed = processed,
                            bytesTotal = declaredSize,
                        )
                    }
                }
                output.flush()
                fileOutput.fd.sync()
            }
        }
        return digest.digest().toHex()
    }

    private fun querySafeDisplayName(source: Uri): String {
        val raw = resolver.querySingle(source, OpenableColumns.DISPLAY_NAME) { cursor, index ->
            cursor.getString(index)
        } ?: "pacote.zip"
        val sanitized = raw
            .take(MAX_DISPLAY_NAME_LENGTH)
            .map { character ->
                if (character in 'A'..'Z' ||
                    character in 'a'..'z' ||
                    character in '0'..'9' ||
                    character in "._-"
                ) {
                    character
                } else {
                    '_'
                }
            }
            .joinToString("")
        return sanitized.ifBlank { "pacote.zip" }
    }

    private fun querySizeForProgress(source: Uri): Long? {
        val size = resolver.querySingle(source, OpenableColumns.SIZE) { cursor, index ->
            if (cursor.isNull(index)) null else cursor.getLong(index)
        }
        return size?.takeIf { it in 1..limits.maxArchiveBytes }
    }

    private fun <T> ContentResolver.querySingle(
        uri: Uri,
        column: String,
        read: (Cursor, Int) -> T?,
    ): T? = try {
        query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(column)
            if (index >= 0 && cursor.moveToFirst()) read(cursor, index) else null
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun ioFailure(): Nothing =
        throw BundleValidationException(BundleFailureCode.IO_ERROR)

    private fun clearStaleTransactions() {
        val children = importsRoot.listFiles() ?: ioFailure()
        children.forEach { child ->
            if (!child.deleteRecursively()) ioFailure()
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 128
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        val TRANSACTION_ID = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )
    }
}

private fun VerificationNotice.emitTo(events: TransactionEventEmitter) {
    when (this) {
        VerificationNotice.SIGNATURE_STARTED -> events.emit(
            phase = OperationPhase.AUTHENTICATE,
            status = OperationStatus.STARTED,
            messageCode = MessageCode.SIGNATURE_CHECK_STARTED,
        )
        VerificationNotice.SIGNATURE_SUCCEEDED -> events.emit(
            phase = OperationPhase.AUTHENTICATE,
            status = OperationStatus.SUCCEEDED,
            messageCode = MessageCode.SIGNATURE_CHECK_SUCCEEDED,
        )
        VerificationNotice.INSPECTION_STARTED -> events.emit(
            phase = OperationPhase.INSPECT,
            status = OperationStatus.STARTED,
            messageCode = MessageCode.ARCHIVE_INSPECTION_STARTED,
        )
        VerificationNotice.INSPECTION_SUCCEEDED -> events.emit(
            phase = OperationPhase.INSPECT,
            status = OperationStatus.SUCCEEDED,
            messageCode = MessageCode.ARCHIVE_INSPECTION_SUCCEEDED,
        )
        VerificationNotice.EXTRACTION_STARTED -> events.emit(
            phase = OperationPhase.EXTRACT,
            status = OperationStatus.STARTED,
            messageCode = MessageCode.FILE_EXTRACTION_STARTED,
        )
        VerificationNotice.EXTRACTION_SUCCEEDED -> events.emit(
            phase = OperationPhase.EXTRACT,
            status = OperationStatus.SUCCEEDED,
            messageCode = MessageCode.FILE_EXTRACTION_SUCCEEDED,
        )
    }
}
