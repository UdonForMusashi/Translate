package dev.translate.installer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.translate.installer.audit.MessageCode
import dev.translate.installer.audit.OperationEvent
import dev.translate.installer.audit.OperationEventSink
import dev.translate.installer.audit.OperationPhase
import dev.translate.installer.audit.OperationStatus
import dev.translate.installer.audit.TransactionEventEmitter
import dev.translate.installer.data.importer.BundleStager
import dev.translate.installer.data.importer.ImportedBundle
import dev.translate.installer.data.importer.LocalBundleImporter
import dev.translate.installer.data.remote.GitHubReleaseClient
import dev.translate.installer.data.remote.RemoteBundleImporter
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.installer.InstallationReceipt
import dev.translate.installer.installer.InstallationReceiptStore
import dev.translate.installer.installer.InstallerCapabilities
import dev.translate.installer.installer.InstallerException
import dev.translate.installer.installer.InstallerPhase
import dev.translate.installer.installer.InstallerProgress
import dev.translate.installer.installer.ReceiptStatus
import dev.translate.installer.installer.ReceiptStoreException
import dev.translate.installer.installer.ShizukuFileInstaller
import dev.translate.installer.security.BundleFailureCode
import dev.translate.installer.security.BundleValidationException
import dev.translate.installer.security.BundleVerifier
import dev.translate.installer.security.ManifestSignatureVerifier
import dev.translate.installer.security.ReleaseKeyProvider
import dev.translate.installer.shizuku.AndroidShizukuGateway
import dev.translate.installer.shizuku.ShizukuGateController
import dev.translate.installer.shizuku.ShizukuGateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ImportUiState(
    val shizukuGate: ShizukuGateState = ShizukuGateState(),
    val selectedProfile: GameProfile? = null,
    val isWorking: Boolean = false,
    val currentPhase: OperationPhase? = null,
    val progress: Float? = null,
    val events: List<OperationEvent> = emptyList(),
    val importedBundle: ImportedBundle? = null,
    val failureCode: BundleFailureCode? = null,
    val installerCapabilities: InstallerCapabilities? = null,
    val installerFailureCode: String? = null,
    val gameClosedConfirmed: Boolean = false,
    val isInstalled: Boolean = false,
    val installationReceipt: InstallationReceipt? = null,
)

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private val bundleStager = BundleStager(
        context = application,
        verifier = BundleVerifier(
            signatureVerifier = ManifestSignatureVerifier(ReleaseKeyProvider),
        ),
    )
    private val localImporter = LocalBundleImporter(application, bundleStager)
    private val remoteImporter = RemoteBundleImporter(GitHubReleaseClient(), bundleStager)
    private val fileInstaller = ShizukuFileInstaller(application)
    private val receiptStore = InstallationReceiptStore(application)
    private var importJob: Job? = null
    private val shizukuGateController = ShizukuGateController(
        gateway = AndroidShizukuGateway(),
        onStateChanged = ::onShizukuGateChanged,
    )

    init {
        shizukuGateController.start()
    }

    fun verifyShizuku() {
        if (_state.value.isWorking) return
        shizukuGateController.verifyOrRequestPermission()
    }

    fun revalidateShizuku() {
        shizukuGateController.revalidateAfterForeground()
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun selectProfile(profile: GameProfile) {
        if (_state.value.isWorking || !_state.value.shizukuGate.isApproved) return
        var receiptInvalid = false
        val receipt = try {
            receiptStore.load(profile)
        } catch (_: ReceiptStoreException) {
            receiptInvalid = true
            null
        }
        _state.update {
            it.copy(
                selectedProfile = profile,
                importedBundle = null,
                failureCode = null,
                events = emptyList(),
                currentPhase = OperationPhase.SELECT,
                progress = null,
                installerCapabilities = null,
                installerFailureCode = "RECEIPT_INVALID".takeIf { receiptInvalid },
                gameClosedConfirmed = false,
                isInstalled = receipt?.status == ReceiptStatus.INSTALLED,
                installationReceipt = receipt,
            )
        }
    }

    fun setGameClosedConfirmed(confirmed: Boolean) {
        if (_state.value.isWorking) return
        _state.update { it.copy(gameClosedConfirmed = confirmed) }
    }

    fun importBundle(uri: Uri) {
        startBundleImport { transactionId, selectedProfile, emitter ->
            localImporter.import(
                source = uri,
                transactionId = transactionId,
                selectedProfile = selectedProfile,
                events = emitter,
            )
        }
    }

    fun downloadLatestBundle() {
        startBundleImport { transactionId, selectedProfile, emitter ->
            remoteImporter.import(
                transactionId = transactionId,
                selectedProfile = selectedProfile,
                events = emitter,
            )
        }
    }

    private fun startBundleImport(
        importAction: suspend (String, GameProfile, TransactionEventEmitter) -> ImportedBundle,
    ) {
        val selectedProfile = _state.value.selectedProfile ?: return
        if (!_state.value.shizukuGate.isApproved || importJob?.isActive == true) return

        val transactionId = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                isWorking = true,
                currentPhase = OperationPhase.SELECT,
                progress = null,
                events = emptyList(),
                importedBundle = null,
                failureCode = null,
                installerCapabilities = null,
                installerFailureCode = null,
                gameClosedConfirmed = false,
            )
        }
        val emitter = TransactionEventEmitter(transactionId, sink = OperationEventSink(::onEvent))
        emitter.emit(
            phase = OperationPhase.SELECT,
            status = OperationStatus.SUCCEEDED,
            messageCode = MessageCode.PROFILE_SELECTED,
            fileRelativePath = selectedProfile.profileId,
        )

        importJob = viewModelScope.launch {
            try {
                val imported = importAction(transactionId, selectedProfile, emitter)
                emitter.emit(
                    phase = OperationPhase.PRECHECK,
                    status = OperationStatus.STARTED,
                    messageCode = MessageCode.PRIVILEGED_PROBE_STARTED,
                )
                val capabilities = try {
                    fileInstaller.probe(
                        selectedProfile,
                        imported.verifiedBundle.manifest.uncompressedSize,
                    )
                } catch (exception: InstallerException) {
                    InstallerCapabilities(false, exception.reasonCode)
                }
                emitter.emit(
                    phase = OperationPhase.PRECHECK,
                    status = if (capabilities.available) OperationStatus.SUCCEEDED
                    else OperationStatus.FAILED,
                    messageCode = if (capabilities.available) MessageCode.PRIVILEGED_PROBE_SUCCEEDED
                    else MessageCode.OPERATION_FAILED,
                    failureCode = capabilities.reasonCode.takeUnless { capabilities.available },
                )
                _state.update {
                    it.copy(
                        isWorking = false,
                        progress = 1f,
                        importedBundle = imported,
                        failureCode = null,
                        installerCapabilities = capabilities,
                        installerFailureCode = capabilities.reasonCode
                            .takeUnless { capabilities.available },
                    )
                }
            } catch (exception: CancellationException) {
                emitter.emit(
                    phase = _state.value.currentPhase ?: OperationPhase.IMPORT,
                    status = OperationStatus.FAILED,
                    messageCode = MessageCode.OPERATION_CANCELLED,
                )
                _state.update {
                    it.copy(isWorking = false, progress = null, importedBundle = null)
                }
                throw exception
            } catch (exception: BundleValidationException) {
                emitter.emit(
                    phase = _state.value.currentPhase ?: OperationPhase.IMPORT,
                    status = OperationStatus.FAILED,
                    messageCode = MessageCode.OPERATION_FAILED,
                    failureCode = exception.code.name,
                )
                _state.update {
                    it.copy(
                        isWorking = false,
                        progress = null,
                        importedBundle = null,
                        failureCode = exception.code,
                    )
                }
            }
        }
    }

    fun installImportedBundle() {
        val snapshot = _state.value
        val profile = snapshot.selectedProfile ?: return
        val imported = snapshot.importedBundle ?: return
        if (!snapshot.shizukuGate.isApproved || snapshot.isWorking ||
            snapshot.installerCapabilities?.available != true || !snapshot.gameClosedConfirmed ||
            snapshot.installationReceipt?.status == ReceiptStatus.PENDING
        ) return

        val previousReceipt = snapshot.installationReceipt
        val receipt = InstallationReceipt.pendingUpdate(
            profile,
            imported.verifiedBundle.manifest,
            previousReceipt,
        )
        val emitter = TransactionEventEmitter(
            UUID.randomUUID().toString(),
            sink = OperationEventSink(::onEvent),
        )
        _state.update {
            it.copy(
                isWorking = true,
                currentPhase = OperationPhase.COMMIT,
                progress = null,
                installerFailureCode = null,
                isInstalled = false,
            )
        }
        importJob = viewModelScope.launch {
            try {
                receiptStore.save(receipt)
                _state.update { it.copy(installationReceipt = receipt) }
                val outcome = fileInstaller.install(
                    profile = profile,
                    bundle = imported.verifiedBundle,
                    progress = { reportInstallerProgress(emitter, it) },
                )
                val installedReceipt = InstallationReceipt.installedUpdate(
                    profile,
                    imported.verifiedBundle.manifest,
                    previousReceipt,
                )
                receiptStore.save(installedReceipt)
                emitter.emit(
                    phase = OperationPhase.VERIFY,
                    status = OperationStatus.SUCCEEDED,
                    messageCode = MessageCode.INSTALLATION_SUCCEEDED,
                )
                _state.update {
                    it.copy(
                        isWorking = false,
                        currentPhase = OperationPhase.VERIFY,
                        progress = 1f,
                        installerFailureCode = null,
                        isInstalled = outcome.installed,
                        installationReceipt = installedReceipt,
                    )
                }
            } catch (exception: CancellationException) {
                emitter.emit(
                    phase = OperationPhase.COMMIT,
                    status = OperationStatus.FAILED,
                    messageCode = MessageCode.OPERATION_CANCELLED,
                )
                _state.update {
                    it.copy(
                        isWorking = false,
                        progress = null,
                        installationReceipt = loadReceiptOr(receipt),
                    )
                }
                throw exception
            } catch (exception: InstallerException) {
                installationFailed(emitter, exception.reasonCode, receipt)
            } catch (_: ReceiptStoreException) {
                installationFailed(emitter, "RECEIPT_WRITE_FAILED", receipt)
            }
        }
    }

    fun uninstallTranslation() {
        val snapshot = _state.value
        val profile = snapshot.selectedProfile ?: return
        val receipt = snapshot.installationReceipt ?: return
        if (receipt.profile != profile || !snapshot.shizukuGate.isApproved ||
            snapshot.isWorking || !snapshot.gameClosedConfirmed
        ) return

        val emitter = TransactionEventEmitter(
            UUID.randomUUID().toString(),
            sink = OperationEventSink(::onEvent),
        )
        _state.update {
            it.copy(
                isWorking = true,
                currentPhase = OperationPhase.CLEANUP,
                progress = null,
                installerFailureCode = null,
            )
        }
        importJob = viewModelScope.launch {
            try {
                fileInstaller.uninstall(profile, receipt.files) {
                    reportInstallerProgress(emitter, it)
                }
                receiptStore.delete(profile)
                emitter.emit(
                    phase = OperationPhase.CLEANUP,
                    status = OperationStatus.SUCCEEDED,
                    messageCode = MessageCode.UNINSTALL_SUCCEEDED,
                )
                _state.update {
                    it.copy(
                        isWorking = false,
                        currentPhase = OperationPhase.CLEANUP,
                        progress = 1f,
                        installerFailureCode = null,
                        isInstalled = false,
                        installationReceipt = null,
                    )
                }
            } catch (exception: InstallerException) {
                operationFailed(emitter, OperationPhase.CLEANUP, exception.reasonCode)
            } catch (_: ReceiptStoreException) {
                operationFailed(emitter, OperationPhase.CLEANUP, "RECEIPT_DELETE_FAILED")
            }
        }
    }

    private fun installationFailed(
        emitter: TransactionEventEmitter,
        reasonCode: String,
        fallbackReceipt: InstallationReceipt,
    ) {
        emitter.emit(
            phase = _state.value.currentPhase ?: OperationPhase.COMMIT,
            status = OperationStatus.FAILED,
            messageCode = MessageCode.OPERATION_FAILED,
            failureCode = reasonCode,
        )
        _state.update {
            it.copy(
                isWorking = false,
                progress = null,
                installerFailureCode = reasonCode,
                isInstalled = false,
                installationReceipt = loadReceiptOr(fallbackReceipt),
            )
        }
    }

    private fun operationFailed(
        emitter: TransactionEventEmitter,
        phase: OperationPhase,
        reasonCode: String,
    ) {
        emitter.emit(
            phase = phase,
            status = OperationStatus.FAILED,
            messageCode = MessageCode.OPERATION_FAILED,
            failureCode = reasonCode,
        )
        _state.update {
            it.copy(isWorking = false, progress = null, installerFailureCode = reasonCode)
        }
    }

    private fun loadReceiptOr(fallback: InstallationReceipt): InstallationReceipt = try {
        receiptStore.load(fallback.profile) ?: fallback
    } catch (_: ReceiptStoreException) {
        fallback
    }

    private fun reportInstallerProgress(
        emitter: TransactionEventEmitter,
        progress: InstallerProgress,
    ) {
        val (phase, message) = when (progress.phase) {
            InstallerPhase.INSTALL -> OperationPhase.COMMIT to MessageCode.COMMIT_PROGRESS
            InstallerPhase.UNINSTALL -> OperationPhase.CLEANUP to MessageCode.UNINSTALL_PROGRESS
        }
        emitter.emit(
            phase = phase,
            status = OperationStatus.PROGRESS,
            messageCode = message,
            fileRelativePath = progress.fileName,
            bytesProcessed = progress.processed,
            bytesTotal = progress.total,
        )
    }

    private fun onEvent(event: OperationEvent) {
        _state.update { previous ->
            val eventProgress = if (event.bytesProcessed != null &&
                event.bytesTotal != null && event.bytesTotal > 0
            ) {
                (event.bytesProcessed.toDouble() / event.bytesTotal.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            } else {
                null
            }
            previous.copy(
                currentPhase = event.phase,
                progress = eventProgress ?: previous.progress.takeIf {
                    previous.currentPhase == event.phase
                },
                events = (previous.events + event).takeLast(MAX_VISIBLE_EVENTS),
            )
        }
    }

    private fun onShizukuGateChanged(gate: ShizukuGateState) {
        val approvalWasLost = _state.value.shizukuGate.isApproved && !gate.isApproved
        if (!gate.isApproved) importJob?.cancel()
        _state.update { previous ->
            if (gate.isApproved) {
                previous.copy(shizukuGate = gate)
            } else {
                previous.copy(
                    shizukuGate = gate,
                    selectedProfile = null,
                    isWorking = false,
                    currentPhase = null,
                    progress = null,
                    importedBundle = null,
                    failureCode = null,
                    installerCapabilities = null,
                    installerFailureCode = null,
                    gameClosedConfirmed = false,
                    isInstalled = false,
                    installationReceipt = null,
                )
            }
        }
        if (approvalWasLost) {
            viewModelScope.launch {
                try {
                    bundleStager.clearPrivateStaging()
                } catch (_: BundleValidationException) {
                }
            }
        }
    }

    override fun onCleared() {
        shizukuGateController.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_VISIBLE_EVENTS = 500
    }
}
