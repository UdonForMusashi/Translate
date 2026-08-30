package dev.translate.installer.data.importer

import android.content.Context
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
import dev.translate.installer.security.VerificationNotice
import dev.translate.installer.security.VerificationNoticeSink
import dev.translate.installer.security.VerifiedBundle
import dev.translate.installer.security.ensureSufficientStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ImportedBundle(
    val verifiedBundle: VerifiedBundle,
    val archiveSha256: String,
    val sourceDisplayName: String,
)

data class BundleSourceResult(
    val sha256: String,
    val displayName: String,
)

class BundleStager(
    context: Context,
    private val verifier: BundleVerifier,
    private val limits: ImportLimits = ImportLimits(),
) {
    private val bundlesRoot = File(context.filesDir, "bundles")
    private val storageMutex = Mutex()

    suspend fun stage(
        transactionId: String,
        selectedProfile: GameProfile,
        events: TransactionEventEmitter,
        writeSource: suspend (File) -> BundleSourceResult,
    ): ImportedBundle = withContext(Dispatchers.IO) {
        storageMutex.withLock {
            stageLocked(transactionId, selectedProfile, events, writeSource)
        }
    }

    private suspend fun stageLocked(
        transactionId: String,
        selectedProfile: GameProfile,
        events: TransactionEventEmitter,
        writeSource: suspend (File) -> BundleSourceResult,
    ): ImportedBundle {
        if (!TRANSACTION_ID.matches(transactionId)) ioFailure()
        if (!bundlesRoot.exists() && !bundlesRoot.mkdirs()) ioFailure()
        if (!bundlesRoot.isDirectory) ioFailure()
        clearStaleTransactions()

        val transactionRoot = File(bundlesRoot, transactionId)
        if (transactionRoot.exists() || !transactionRoot.mkdir()) ioFailure()

        val partial = File(transactionRoot, "bundle.zip.partial")
        val archive = File(transactionRoot, "bundle.zip")
        val extracted = File(transactionRoot, "extracted")

        return try {
            ensureSufficientStorage(transactionRoot, 0, limits)
            val sourceResult = writeSource(partial)
            if (!partial.isFile || partial.length() <= 0) {
                throw BundleValidationException(BundleFailureCode.ARCHIVE_INVALID)
            }
            if (partial.length() > limits.maxArchiveBytes) {
                throw BundleValidationException(BundleFailureCode.ARCHIVE_TOO_LARGE)
            }
            if (!partial.renameTo(archive)) ioFailure()

            var lastProgressAtNanos = 0L
            val verified = verifier.verifyAndExtract(
                archiveFile = archive,
                extractionDirectory = extracted,
                selectedProfile = selectedProfile,
                noticeSink = VerificationNoticeSink { notice -> notice.emitTo(events) },
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
            ImportedBundle(
                verifiedBundle = verified,
                archiveSha256 = sourceResult.sha256,
                sourceDisplayName = sourceResult.displayName,
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
            if (!bundlesRoot.exists()) return@withLock
            if (!bundlesRoot.isDirectory) ioFailure()
            clearStaleTransactions()
        }
    }

    private fun clearStaleTransactions() {
        val children = bundlesRoot.listFiles() ?: ioFailure()
        children.forEach { child ->
            if (!child.deleteRecursively()) ioFailure()
        }
    }

    private fun ioFailure(): Nothing =
        throw BundleValidationException(BundleFailureCode.IO_ERROR)

    private companion object {
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
