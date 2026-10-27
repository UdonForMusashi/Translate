package dev.translate.installer.audit

import org.junit.Assert.assertEquals
import org.junit.Test

class OperationEventLogTest {
    @Test fun `replaces consecutive live progress even when current file changes`() {
        val first = event(sequence = 1, file = "first.bin")
        val second = event(sequence = 2, file = "second.bin")

        val events = appendVisibleEvent(
            appendVisibleEvent(emptyList(), first, maximum = 10),
            second,
            maximum = 10,
        )

        assertEquals(listOf(second), events)
    }

    @Test fun `keeps progress from different phases and lifecycle events`() {
        val download = event(sequence = 1, phase = OperationPhase.DOWNLOAD)
        val extract = event(
            sequence = 2,
            phase = OperationPhase.EXTRACT,
            message = MessageCode.FILE_EXTRACTION_PROGRESS,
        )
        val succeeded = event(
            sequence = 3,
            phase = OperationPhase.EXTRACT,
            status = OperationStatus.SUCCEEDED,
            message = MessageCode.FILE_EXTRACTION_SUCCEEDED,
        )

        val events = listOf(download, extract).let {
            appendVisibleEvent(it, succeeded, maximum = 10)
        }

        assertEquals(listOf(download, extract, succeeded), events)
    }

    @Test fun `does not combine progress from different transactions`() {
        val previous = event(sequence = 1, transaction = "one")
        val current = event(sequence = 1, transaction = "two")

        assertEquals(
            listOf(previous, current),
            appendVisibleEvent(listOf(previous), current, maximum = 10),
        )
    }

    private fun event(
        sequence: Long,
        transaction: String = "transaction",
        phase: OperationPhase = OperationPhase.COMMIT,
        status: OperationStatus = OperationStatus.PROGRESS,
        message: MessageCode = MessageCode.COMMIT_PROGRESS,
        file: String? = null,
    ) = OperationEvent(
        transactionId = transaction,
        sequence = sequence,
        elapsedRealtimeMs = sequence,
        phase = phase,
        status = status,
        messageCode = message,
        fileRelativePath = file,
    )
}
