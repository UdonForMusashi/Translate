package dev.translate.installer.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.translate.installer.audit.MessageCode
import dev.translate.installer.audit.OperationEvent
import dev.translate.installer.audit.OperationEventSink
import dev.translate.installer.audit.OperationPhase
import dev.translate.installer.audit.OperationStatus
import dev.translate.installer.audit.TransactionEventEmitter
import dev.translate.installer.audit.appendVisibleEvent
import dev.translate.installer.data.GamePreferences
import dev.translate.installer.data.importer.BundleStager
import dev.translate.installer.data.remote.CatalogReleaseCache
import dev.translate.installer.data.remote.DownloadSnapshot
import dev.translate.installer.data.remote.GitHubReleaseClient
import dev.translate.installer.data.remote.RemoteBundleImporter
import dev.translate.installer.data.remote.RemoteImportedBundle
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.download.BackgroundDownloads
import dev.translate.installer.installer.DirectFileInstaller
import dev.translate.installer.installer.FileInstaller
import dev.translate.installer.installer.InstallationAccessMode
import dev.translate.installer.installer.InstallationReceipt
import dev.translate.installer.installer.InstallationReceiptStore
import dev.translate.installer.installer.InstallerCapabilities
import dev.translate.installer.installer.InstallerException
import dev.translate.installer.installer.InstallerPhase
import dev.translate.installer.installer.InstallerProgress
import dev.translate.installer.installer.LegacyDirectAccessPolicy
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportUiState(
    val accessMode: InstallationAccessMode = InstallationAccessMode.SHIZUKU,
    val legacyStoragePermissionGranted: Boolean = false,
    val shizukuGate: ShizukuGateState = ShizukuGateState(),
    val selectedProfile: GameProfile? = null,
    val isWorking: Boolean = false,
    val currentPhase: OperationPhase? = null,
    val progress: Float? = null,
    val events: List<OperationEvent> = emptyList(),
    val importedBundle: RemoteImportedBundle? = null,
    val failureCode: BundleFailureCode? = null,
    val installerCapabilities: InstallerCapabilities? = null,
    val installerFailureCode: String? = null,
    val gameClosedConfirmed: Boolean = false,
    val isInstalled: Boolean = false,
    val installationReceipt: InstallationReceipt? = null,
    val catalog: Map<GameProfile, ProfileSummary> = emptyMap(),
    val rememberedFgo: GameProfile? = null,
    val rememberedBlueArchive: GameProfile? = null,
    val backgroundDownload: DownloadSnapshot = DownloadSnapshot(),
) {
    val installationAccessApproved: Boolean
        get() =
            when (accessMode) {
                InstallationAccessMode.LEGACY_DIRECT -> legacyStoragePermissionGranted
                InstallationAccessMode.SHIZUKU -> shizukuGate.isApproved
            }
}

class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val applicationContext = application.applicationContext
    private val preferences = GamePreferences(application)
    private val releaseCache = CatalogReleaseCache(application)
    private var activeGame: GameProfile? = null
    private var catalogJob: Job? = null
    private val accessMode = LegacyDirectAccessPolicy.modeFor(Build.VERSION.SDK_INT)
    private val _state =
        MutableStateFlow(
            ImportUiState(
                accessMode = accessMode,
                rememberedFgo = preferences.selectedFor(GameProfile.JP),
                rememberedBlueArchive = preferences.selectedFor(GameProfile.BA),
            )
        )
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private val bundleStager =
        BundleStager(
            context = application,
            verifier =
                BundleVerifier(signatureVerifier = ManifestSignatureVerifier(ReleaseKeyProvider)),
        )
    private val remoteImporter =
        RemoteBundleImporter(
            GitHubReleaseClient(),
            bundleStager,
            transport = { profile, asset, destination, progress ->
                BackgroundDownloads.copyToStaging(
                    applicationContext,
                    profile,
                    asset,
                    destination,
                    progress,
                )
            },
        )
    private val fileInstaller: FileInstaller =
        when (accessMode) {
            InstallationAccessMode.LEGACY_DIRECT -> DirectFileInstaller(application)
            InstallationAccessMode.SHIZUKU -> ShizukuFileInstaller(application)
        }
    private val receiptStore = InstallationReceiptStore(application)
    private var importJob: Job? = null
    private val shizukuGateController =
        ShizukuGateController(
            gateway = AndroidShizukuGateway(),
            onStateChanged = ::onShizukuGateChanged,
        )

    init {
        BackgroundDownloads.restore(application)
        viewModelScope.launch {
            BackgroundDownloads.state.collect { snapshot ->
                _state.update { it.copy(backgroundDownload = snapshot) }
            }
        }
        refreshCatalog()
        if (accessMode == InstallationAccessMode.SHIZUKU) {
            shizukuGateController.start()
        }
    }

    fun verifyShizuku() {
        if (accessMode != InstallationAccessMode.SHIZUKU || _state.value.isWorking) return
        shizukuGateController.verifyOrRequestPermission()
    }

    fun revalidateShizuku() {
        if (accessMode != InstallationAccessMode.SHIZUKU) return
        shizukuGateController.revalidateAfterForeground()
    }

    fun updateLegacyStoragePermission(granted: Boolean) {
        if (accessMode != InstallationAccessMode.LEGACY_DIRECT) return
        val approvalWasLost = _state.value.legacyStoragePermissionGranted && !granted
        if (approvalWasLost) BackgroundDownloads.cancel(applicationContext)
        if (!granted) importJob?.cancel()
        _state.update { previous ->
            if (granted) {
                previous.copy(legacyStoragePermissionGranted = true)
            } else {
                previous.copy(
                    legacyStoragePermissionGranted = false,
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
        if (approvalWasLost) clearPrivateStagingAfterAccessLoss()
        if (granted) restoreGameChoice()
    }

    fun cancelImport() {
        BackgroundDownloads.cancel(applicationContext)
        importJob?.cancel()
    }

    fun clearProfileSelection() {
        if (_state.value.isWorking) return
        activeGame = null
        _state.update {
            it.copy(
                selectedProfile = null,
                currentPhase = null,
                progress = null,
                events = emptyList(),
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

    fun resetCompletedInstallationUi() {
        val snapshot = _state.value
        if (
            snapshot.isWorking ||
                !snapshot.isInstalled ||
                snapshot.installationReceipt?.status != ReceiptStatus.INSTALLED ||
                snapshot.installerFailureCode != null
        )
            return
        _state.update {
            it.copy(
                currentPhase = null,
                progress = null,
                installerCapabilities = null,
                gameClosedConfirmed = false,
            )
        }
    }

    fun selectProfile(profile: GameProfile) {
        val keepBetterFgo = _state.value.selectedProfile?.isBetterFgo == true
        selectInstallationProfile(profile.withBetterFgo(keepBetterFgo))
    }

    fun setBetterFgoEnabled(enabled: Boolean) {
        val current = _state.value.selectedProfile ?: return
        if (current.bundleProfile !in setOf(GameProfile.JP, GameProfile.NA)) return
        selectInstallationProfile(current.withBetterFgo(enabled))
    }

    private fun selectInstallationProfile(profile: GameProfile) {
        if (
            !profile.isUserSelectable ||
                _state.value.isWorking ||
                !_state.value.installationAccessApproved
        )
            return
        if (_state.value.selectedProfile == profile) return
        var receiptInvalid = false
        val receipt =
            try {
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
                rememberedFgo =
                    if (profile.bundleProfile != GameProfile.BA) profile else it.rememberedFgo,
                rememberedBlueArchive =
                    if (profile.bundleProfile == GameProfile.BA) profile
                    else it.rememberedBlueArchive,
            )
        }
        preferences.remember(profile)
    }

    fun openGame(profile: GameProfile) {
        if (_state.value.isWorking) return
        clearProfileSelection()
        activeGame = profile.bundleProfile
        restoreGameChoice()
    }

    private fun restoreGameChoice() {
        val game = activeGame ?: return
        if (!_state.value.installationAccessApproved || _state.value.selectedProfile != null) return
        val pending =
            _state.value.backgroundDownload
                .takeIf {
                    it.status !in
                        setOf(
                            dev.translate.installer.data.remote.DownloadStatus.IDLE,
                            dev.translate.installer.data.remote.DownloadStatus.CANCELLED,
                        ) &&
                        (it.profile?.bundleProfile == GameProfile.BA) ==
                            (game.bundleProfile == GameProfile.BA)
                }
                ?.profile
        (pending ?: preferences.selectedFor(game))?.let(::selectInstallationProfile)
    }

    fun refreshCatalog(force: Boolean = false) {
        if (catalogJob?.isActive == true) return
        catalogJob =
            viewModelScope.launch {
                val receipts =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        GameProfile.entries.associateWith { profile ->
                            try {
                                val receipt = receiptStore.load(profile)
                                ProfileSummary(
                                    receipt?.bundleVersion,
                                    receipt?.status,
                                    checking = true,
                                    installedAssetId =
                                        preferences.installedAsset(profile, receipt?.bundleVersion),
                                )
                            } catch (_: ReceiptStoreException) {
                                ProfileSummary(receiptInvalid = true, checking = true)
                            }
                        }
                    }
                _state.update { it.copy(catalog = receipts) }
                for (base in listOf(GameProfile.JP, GameProfile.NA, GameProfile.BA)) {
                    try {
                        val latest =
                            (if (!force) releaseCache.read(base) else null)
                                ?: GitHubReleaseClient().latest(base).also {
                                    releaseCache.save(base, it)
                                }
                        _state.update { previous ->
                            previous.copy(
                                catalog =
                                    previous.catalog.mapValues { (profile, summary) ->
                                        if (profile.bundleProfile == base)
                                            summary.copy(
                                                latest = latest,
                                                checking = false,
                                                lookupFailure = null,
                                            )
                                        else summary
                                    }
                            )
                        }
                    } catch (exception: BundleValidationException) {
                        _state.update { previous ->
                            previous.copy(
                                catalog =
                                    previous.catalog.mapValues { (profile, summary) ->
                                        if (profile.bundleProfile == base)
                                            summary.copy(
                                                checking = false,
                                                lookupFailure = exception.code,
                                            )
                                        else summary
                                    }
                            )
                        }
                    }
                }
            }
    }

    fun setGameClosedConfirmed(confirmed: Boolean) {
        if (_state.value.isWorking) return
        _state.update { it.copy(gameClosedConfirmed = confirmed) }
    }

    fun downloadLatestBundle() {
        startBundleDownload()
    }

    private fun startBundleDownload() {
        val selectedProfile = _state.value.selectedProfile ?: return
        if (
            !selectedProfile.isUserSelectable ||
                !_state.value.installationAccessApproved ||
                importJob?.isActive == true
        )
            return

        BackgroundDownloads.begin(applicationContext, selectedProfile)

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

        importJob =
            viewModelScope.launch {
                try {
                    val imported =
                        remoteImporter.import(
                            transactionId = transactionId,
                            selectedProfile = selectedProfile,
                            events = emitter,
                        )
                    val capabilities = probeCapabilities(selectedProfile, imported, emitter)
                    _state.update {
                        it.copy(
                            isWorking = false,
                            progress = 1f,
                            importedBundle = imported,
                            failureCode = null,
                            installerCapabilities = capabilities,
                            installerFailureCode =
                                capabilities.reasonCode.takeUnless { capabilities.available },
                        )
                    }
                } catch (exception: CancellationException) {
                    emitter.emit(
                        phase = _state.value.currentPhase ?: OperationPhase.DOWNLOAD,
                        status = OperationStatus.FAILED,
                        messageCode = MessageCode.OPERATION_CANCELLED,
                    )
                    _state.update {
                        it.copy(isWorking = false, progress = null, importedBundle = null)
                    }
                    throw exception
                } catch (exception: BundleValidationException) {
                    if (
                        exception.code !in
                            setOf(
                                BundleFailureCode.NETWORK_UNAVAILABLE,
                                BundleFailureCode.DOWNLOAD_FAILED,
                                BundleFailureCode.DOWNLOAD_SIZE_MISMATCH,
                                BundleFailureCode.RELEASE_CHANGED_DURING_DOWNLOAD,
                                BundleFailureCode.RELEASE_RATE_LIMITED,
                                BundleFailureCode.RELEASE_NOT_FOUND,
                                BundleFailureCode.RELEASE_REQUEST_FAILED,
                            )
                    ) {
                        BackgroundDownloads.discardCompleted(applicationContext, selectedProfile)
                    }
                    emitter.emit(
                        phase = _state.value.currentPhase ?: OperationPhase.DOWNLOAD,
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
        val cachedBundle = snapshot.importedBundle ?: return
        if (
            !snapshot.installationAccessApproved ||
                snapshot.isWorking ||
                snapshot.installerCapabilities?.available != true ||
                !snapshot.gameClosedConfirmed ||
                snapshot.installationReceipt?.status == ReceiptStatus.PENDING
        )
            return

        val previousReceipt = snapshot.installationReceipt
        BackgroundDownloads.begin(applicationContext, profile)
        val emitter =
            TransactionEventEmitter(
                UUID.randomUUID().toString(),
                sink = OperationEventSink(::onEvent),
            )
        _state.update {
            it.copy(
                isWorking = true,
                currentPhase = OperationPhase.DOWNLOAD,
                progress = null,
                events = emptyList(),
                failureCode = null,
                installerFailureCode = null,
            )
        }
        importJob =
            viewModelScope.launch {
                var readyBundle = cachedBundle
                var pendingReceipt: InstallationReceipt? = null
                var gameFilesInstalled = false
                try {
                    readyBundle = ensureLatestBundle(profile, readyBundle, emitter)

                    val capabilities = probeCapabilities(profile, readyBundle, emitter)
                    _state.update {
                        it.copy(
                            importedBundle = readyBundle,
                            installerCapabilities = capabilities,
                            installerFailureCode =
                                capabilities.reasonCode.takeUnless { capabilities.available },
                        )
                    }
                    if (!capabilities.available) {
                        _state.update { it.copy(isWorking = false, progress = null) }
                        return@launch
                    }

                    val receipt =
                        InstallationReceipt.pendingUpdate(
                            profile,
                            readyBundle.verifiedBundle.manifest,
                            previousReceipt,
                        )
                    pendingReceipt = receipt
                    receiptStore.save(receipt)
                    _state.update { it.copy(installationReceipt = receipt) }
                    val outcome =
                        fileInstaller.install(
                            profile = profile,
                            bundle = readyBundle.verifiedBundle,
                            progress = { reportInstallerProgress(emitter, it) },
                        )
                    gameFilesInstalled = outcome.installed
                    val installedReceipt =
                        InstallationReceipt.installedUpdate(
                            profile,
                            readyBundle.verifiedBundle.manifest,
                            previousReceipt,
                        )
                    receiptStore.save(installedReceipt)
                    preferences.rememberInstalled(
                        profile,
                        installedReceipt.bundleVersion,
                        readyBundle.releaseAsset.id,
                    )
                    emitter.emit(
                        phase = OperationPhase.VERIFY,
                        status = OperationStatus.SUCCEEDED,
                        messageCode = MessageCode.INSTALLATION_SUCCEEDED,
                    )
                    val cacheCleared = clearCacheAfterInstallation(emitter)
                    _state.update {
                        it.copy(
                            isWorking = false,
                            currentPhase = OperationPhase.CLEANUP,
                            progress = if (cacheCleared) 1f else null,
                            failureCode = null,
                            installerCapabilities = null,
                            installerFailureCode = if (cacheCleared) null else "CACHE_CLEAR_FAILED",
                            isInstalled = outcome.installed,
                            importedBundle = null,
                            installationReceipt = installedReceipt,
                            gameClosedConfirmed = false,
                        )
                    }
                    _state.update { previous ->
                        previous.copy(
                            catalog =
                                previous.catalog +
                                    (profile to
                                        (previous.catalog[profile] ?: ProfileSummary()).copy(
                                            installedVersion = installedReceipt.bundleVersion,
                                            receiptStatus = ReceiptStatus.INSTALLED,
                                            installedAssetId = readyBundle.releaseAsset.id,
                                        ))
                        )
                    }
                } catch (exception: CancellationException) {
                    emitter.emit(
                        phase = _state.value.currentPhase ?: OperationPhase.DOWNLOAD,
                        status = OperationStatus.FAILED,
                        messageCode = MessageCode.OPERATION_CANCELLED,
                    )
                    _state.update {
                        it.copy(
                            isWorking = false,
                            progress = null,
                            installationReceipt =
                                pendingReceipt?.let(::loadReceiptOr) ?: previousReceipt,
                        )
                    }
                    throw exception
                } catch (exception: BundleValidationException) {
                    emitter.emit(
                        phase = _state.value.currentPhase ?: OperationPhase.DOWNLOAD,
                        status = OperationStatus.FAILED,
                        messageCode = MessageCode.OPERATION_FAILED,
                        failureCode = exception.code.name,
                    )
                    _state.update {
                        it.copy(isWorking = false, progress = null, failureCode = exception.code)
                    }
                } catch (exception: InstallerException) {
                    installationFailed(
                        emitter,
                        exception.reasonCode,
                        pendingReceipt
                            ?: InstallationReceipt.pendingUpdate(
                                profile,
                                readyBundle.verifiedBundle.manifest,
                                previousReceipt,
                            ),
                    )
                } catch (_: ReceiptStoreException) {
                    installationFailed(
                        emitter,
                        if (gameFilesInstalled) {
                            "RECEIPT_FINALIZE_FAILED"
                        } else {
                            "RECEIPT_WRITE_FAILED"
                        },
                        pendingReceipt
                            ?: InstallationReceipt.pendingUpdate(
                                profile,
                                readyBundle.verifiedBundle.manifest,
                                previousReceipt,
                            ),
                    )
                }
            }
    }

    private suspend fun clearCacheAfterInstallation(emitter: TransactionEventEmitter): Boolean {
        emitter.emit(
            phase = OperationPhase.CLEANUP,
            status = OperationStatus.STARTED,
            messageCode = MessageCode.CACHE_CLEAR_STARTED,
        )
        return try {
            bundleStager.clearPrivateStaging()
            _state.value.selectedProfile?.let {
                if (BackgroundDownloads.state.value.profile?.bundleProfile == it.bundleProfile)
                    BackgroundDownloads.cancel(applicationContext)
                BackgroundDownloads.discardCompleted(applicationContext, it)
            }
            emitter.emit(
                phase = OperationPhase.CLEANUP,
                status = OperationStatus.SUCCEEDED,
                messageCode = MessageCode.CACHE_CLEAR_SUCCEEDED,
            )
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            val failureCode =
                (exception as? BundleValidationException)?.code ?: BundleFailureCode.IO_ERROR
            emitter.emit(
                phase = OperationPhase.CLEANUP,
                status = OperationStatus.FAILED,
                messageCode = MessageCode.CACHE_CLEAR_FAILED,
                failureCode = failureCode.name,
            )
            false
        }
    }

    private suspend fun ensureLatestBundle(
        profile: GameProfile,
        cachedBundle: RemoteImportedBundle,
        emitter: TransactionEventEmitter,
    ): RemoteImportedBundle {
        var readyBundle = cachedBundle
        repeat(MAX_RELEASE_REFRESH_CHECKS) { index ->
            val latest = remoteImporter.latest(profile, emitter)
            if (readyBundle.isCurrent(latest)) {
                emitter.emit(
                    phase = OperationPhase.DOWNLOAD,
                    status = OperationStatus.SUCCEEDED,
                    messageCode = MessageCode.CACHED_RELEASE_CURRENT,
                    fileRelativePath = latest.name,
                    bytesTotal = latest.size,
                )
                return readyBundle
            }

            emitter.emit(
                phase = OperationPhase.DOWNLOAD,
                status = OperationStatus.SKIPPED,
                messageCode = MessageCode.CACHED_RELEASE_STALE,
                fileRelativePath = readyBundle.releaseAsset.name,
            )
            bundleStager.clearPrivateStaging()
            _state.update {
                it.copy(importedBundle = null, installerCapabilities = null, progress = null)
            }
            if (index == MAX_RELEASE_REFRESH_CHECKS - 1) {
                throw BundleValidationException(BundleFailureCode.RELEASE_CHANGED_DURING_DOWNLOAD)
            }
            readyBundle =
                remoteImporter.download(
                    transactionId = UUID.randomUUID().toString(),
                    selectedProfile = profile,
                    asset = latest,
                    events = emitter,
                )
            _state.update { it.copy(importedBundle = readyBundle) }
        }
        throw BundleValidationException(BundleFailureCode.RELEASE_CHANGED_DURING_DOWNLOAD)
    }

    private suspend fun probeCapabilities(
        profile: GameProfile,
        bundle: RemoteImportedBundle,
        emitter: TransactionEventEmitter,
    ): InstallerCapabilities {
        emitter.emit(
            phase = OperationPhase.PRECHECK,
            status = OperationStatus.STARTED,
            messageCode = MessageCode.PRIVILEGED_PROBE_STARTED,
        )
        val capabilities =
            try {
                fileInstaller.probe(
                    profile,
                    bundle.verifiedBundle.manifest.uncompressedSize,
                    bundle.verifiedBundle.manifest.files.map { it.name },
                )
            } catch (exception: InstallerException) {
                InstallerCapabilities(false, exception.reasonCode)
            }
        emitter.emit(
            phase = OperationPhase.PRECHECK,
            status =
                if (capabilities.available) OperationStatus.SUCCEEDED else OperationStatus.FAILED,
            messageCode =
                if (capabilities.available) MessageCode.PRIVILEGED_PROBE_SUCCEEDED
                else MessageCode.OPERATION_FAILED,
            failureCode = capabilities.reasonCode.takeUnless { capabilities.available },
        )
        return capabilities
    }

    fun uninstallTranslation() {
        val snapshot = _state.value
        val profile = snapshot.selectedProfile ?: return
        val receipt = snapshot.installationReceipt ?: return
        if (
            receipt.profile != profile || !snapshot.installationAccessApproved || snapshot.isWorking
        )
            return

        val emitter =
            TransactionEventEmitter(
                UUID.randomUUID().toString(),
                sink = OperationEventSink(::onEvent),
            )
        _state.update {
            it.copy(
                isWorking = true,
                currentPhase = OperationPhase.CLEANUP,
                progress = null,
                events = emptyList(),
                installerFailureCode = null,
            )
        }
        importJob =
            viewModelScope.launch {
                try {
                    fileInstaller.uninstall(profile, receipt.files) {
                        reportInstallerProgress(emitter, it)
                    }
                    receiptStore.delete(profile)
                    preferences.forgetInstalled(profile)
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
                    _state.update { previous ->
                        previous.copy(
                            catalog =
                                previous.catalog +
                                    (profile to
                                        (previous.catalog[profile] ?: ProfileSummary()).copy(
                                            installedVersion = null,
                                            receiptStatus = null,
                                            installedAssetId = null,
                                        ))
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

    private fun loadReceiptOr(fallback: InstallationReceipt): InstallationReceipt =
        try {
            receiptStore.load(fallback.profile) ?: fallback
        } catch (_: ReceiptStoreException) {
            fallback
        }

    private fun reportInstallerProgress(
        emitter: TransactionEventEmitter,
        progress: InstallerProgress,
    ) {
        val (phase, message) =
            when (progress.phase) {
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
            val eventProgress =
                if (
                    event.bytesProcessed != null && event.bytesTotal != null && event.bytesTotal > 0
                ) {
                    (event.bytesProcessed.toDouble() / event.bytesTotal.toDouble())
                        .coerceIn(0.0, 1.0)
                        .toFloat()
                } else {
                    null
                }
            previous.copy(
                currentPhase = event.phase,
                progress =
                    eventProgress
                        ?: previous.progress.takeIf { previous.currentPhase == event.phase },
                events = appendVisibleEvent(previous.events, event, MAX_VISIBLE_EVENTS),
            )
        }
    }

    private fun onShizukuGateChanged(gate: ShizukuGateState) {
        if (accessMode != InstallationAccessMode.SHIZUKU) return
        val approvalWasLost = _state.value.shizukuGate.isApproved && !gate.isApproved
        if (!gate.isApproved) importJob?.cancel()
        if (approvalWasLost) BackgroundDownloads.cancel(applicationContext)
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
        if (approvalWasLost) clearPrivateStagingAfterAccessLoss()
        if (gate.isApproved) restoreGameChoice()
    }

    private fun clearPrivateStagingAfterAccessLoss() {
        viewModelScope.launch {
            try {
                bundleStager.clearPrivateStaging()
            } catch (_: BundleValidationException) {}
        }
    }

    override fun onCleared() {
        if (accessMode == InstallationAccessMode.SHIZUKU) {
            shizukuGateController.stop()
        }
        super.onCleared()
    }

    private companion object {
        const val MAX_VISIBLE_EVENTS = 500
        const val MAX_RELEASE_REFRESH_CHECKS = 3
    }
}
