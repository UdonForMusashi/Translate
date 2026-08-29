package dev.translate.installer.audit

import java.util.concurrent.atomic.AtomicLong

enum class OperationPhase {
    SELECT,
    IMPORT,
    AUTHENTICATE,
    INSPECT,
    EXTRACT,
    PRECHECK,
    COMMIT,
    VERIFY,
    CLEANUP,
}

enum class OperationStatus {
    STARTED,
    PROGRESS,
    SUCCEEDED,
    FAILED,
    SKIPPED,
}

enum class MessageCode {
    PROFILE_SELECTED,
    SOURCE_COPY_STARTED,
    SOURCE_COPY_PROGRESS,
    SOURCE_COPY_SUCCEEDED,
    SIGNATURE_CHECK_STARTED,
    SIGNATURE_CHECK_SUCCEEDED,
    ARCHIVE_INSPECTION_STARTED,
    ARCHIVE_INSPECTION_SUCCEEDED,
    FILE_EXTRACTION_STARTED,
    FILE_EXTRACTION_PROGRESS,
    FILE_EXTRACTION_SUCCEEDED,
    BUNDLE_READY,
    PRIVILEGED_PROBE_STARTED,
    PRIVILEGED_PROBE_SUCCEEDED,
    COMMIT_PROGRESS,
    INSTALLATION_SUCCEEDED,
    UNINSTALL_PROGRESS,
    UNINSTALL_SUCCEEDED,
    OPERATION_FAILED,
    OPERATION_CANCELLED,
}

data class OperationEvent(
    val transactionId: String,
    val sequence: Long,
    val elapsedRealtimeMs: Long,
    val phase: OperationPhase,
    val status: OperationStatus,
    val messageCode: MessageCode,
    val fileRelativePath: String? = null,
    val bytesProcessed: Long? = null,
    val bytesTotal: Long? = null,
    val failureCode: String? = null,
)

fun interface OperationEventSink {
    fun emit(event: OperationEvent)
}

class TransactionEventEmitter(
    private val transactionId: String,
    private val startedAtNanos: Long = System.nanoTime(),
    private val sink: OperationEventSink,
) {
    private val sequence = AtomicLong(0)

    fun emit(
        phase: OperationPhase,
        status: OperationStatus,
        messageCode: MessageCode,
        fileRelativePath: String? = null,
        bytesProcessed: Long? = null,
        bytesTotal: Long? = null,
        failureCode: String? = null,
    ) {
        sink.emit(
            OperationEvent(
                transactionId = transactionId,
                sequence = sequence.incrementAndGet(),
                elapsedRealtimeMs = (System.nanoTime() - startedAtNanos) / 1_000_000,
                phase = phase,
                status = status,
                messageCode = messageCode,
                fileRelativePath = fileRelativePath,
                bytesProcessed = bytesProcessed,
                bytesTotal = bytesTotal,
                failureCode = failureCode,
            ),
        )
    }
}
