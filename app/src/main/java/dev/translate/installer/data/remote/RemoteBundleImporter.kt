package dev.translate.installer.data.remote

import dev.translate.installer.audit.MessageCode
import dev.translate.installer.audit.OperationPhase
import dev.translate.installer.audit.OperationStatus
import dev.translate.installer.audit.TransactionEventEmitter
import dev.translate.installer.data.importer.BundleSourceResult
import dev.translate.installer.data.importer.BundleStager
import dev.translate.installer.data.importer.ImportedBundle
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.security.ImportLimits
import dev.translate.installer.security.ensureSufficientStorage

class RemoteBundleImporter(
    private val client: GitHubReleaseClient,
    private val stager: BundleStager,
    private val limits: ImportLimits = ImportLimits(),
) {
    suspend fun import(
        transactionId: String,
        selectedProfile: GameProfile,
        events: TransactionEventEmitter,
    ): ImportedBundle = stager.stage(transactionId, selectedProfile, events) { partial ->
        events.emit(
            phase = OperationPhase.DOWNLOAD,
            status = OperationStatus.STARTED,
            messageCode = MessageCode.RELEASE_LOOKUP_STARTED,
        )
        val asset = client.latest(selectedProfile)
        events.emit(
            phase = OperationPhase.DOWNLOAD,
            status = OperationStatus.SUCCEEDED,
            messageCode = MessageCode.RELEASE_LOOKUP_SUCCEEDED,
            fileRelativePath = asset.tagName,
            bytesTotal = asset.size,
        )
        ensureSufficientStorage(
            directory = partial.parentFile ?: partial,
            requiredBytes = asset.size,
            limits = limits,
        )
        events.emit(
            phase = OperationPhase.DOWNLOAD,
            status = OperationStatus.STARTED,
            messageCode = MessageCode.DOWNLOAD_STARTED,
            fileRelativePath = asset.name,
            bytesTotal = asset.size,
        )
        val result = client.download(
            profile = selectedProfile,
            asset = asset,
            destination = partial,
            progress = DownloadProgressSink { processed, total ->
                events.emit(
                    phase = OperationPhase.DOWNLOAD,
                    status = OperationStatus.PROGRESS,
                    messageCode = MessageCode.DOWNLOAD_PROGRESS,
                    fileRelativePath = asset.name,
                    bytesProcessed = processed,
                    bytesTotal = total,
                )
            },
        )
        events.emit(
            phase = OperationPhase.DOWNLOAD,
            status = OperationStatus.SUCCEEDED,
            messageCode = MessageCode.DOWNLOAD_SUCCEEDED,
            fileRelativePath = asset.name,
            bytesProcessed = result.bytes,
            bytesTotal = asset.size,
        )
        BundleSourceResult(result.sha256, asset.name)
    }
}
