package dev.translate.installer.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.translate.installer.audit.OperationPhase
import dev.translate.installer.data.importer.ImportedBundle
import dev.translate.installer.data.remote.DownloadSnapshot
import dev.translate.installer.data.remote.DownloadStatus
import dev.translate.installer.data.remote.ReleaseAsset
import dev.translate.installer.data.remote.RemoteImportedBundle
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.installer.*
import dev.translate.installer.security.*
import dev.translate.installer.ui.theme.FateCafeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI contracts only: callbacks are fakes and never download or change game files. */
@RunWith(AndroidJUnit4::class)
class InstallerFlowTest {
    @get:Rule val compose = createComposeRule()
    private var ui by mutableStateOf(ImportUiState())
    private var downloads = 0
    private var installs = 0
    private var removals = 0

    private fun launch(initial: ImportUiState = ImportUiState()) {
        ui = initial
        compose.setContent {
            FateCafeTheme {
                FateCafeApp(
                    ui,
                    onVerifyAccess = {
                        ui =
                            ui.copy(
                                accessMode = InstallationAccessMode.LEGACY_DIRECT,
                                legacyStoragePermissionGranted = true,
                            )
                    },
                    onProfileSelected = {
                        ui =
                            ui.copy(
                                selectedProfile = it,
                                installationReceipt = null,
                                isInstalled = false,
                            )
                    },
                    onBetterFgoChanged = {
                        ui = ui.copy(selectedProfile = ui.selectedProfile?.withBetterFgo(it))
                    },
                    onClearProfileSelection = { ui = ui.copy(selectedProfile = null) },
                    onResetCompletedInstallation = {},
                    onDownloadLatest = { downloads++ },
                    onCancelImport = {},
                    onGameClosedChanged = { ui = ui.copy(gameClosedConfirmed = it) },
                    onInstall = { installs++ },
                    onUninstall = { removals++ },
                    onOpenGame = { game ->
                        val remembered =
                            if (game.bundleProfile == GameProfile.BA) ui.rememberedBlueArchive
                            else ui.rememberedFgo
                        if (ui.installationAccessApproved && remembered != null)
                            ui = ui.copy(selectedProfile = remembered)
                    },
                )
            }
        }
    }

    private fun openFgo() =
        compose
            .onNodeWithContentDescription(
                "Fate/Grand Order, Japão e América do Norte. Selecionar jogo"
            )
            .performClick()

    private fun chooseVersion() {
        openFgo()
        compose.onNodeWithText("América do Norte").performClick()
        compose.onNodeWithText("Continuar").performClick()
    }

    private fun approved() =
        ImportUiState(
            accessMode = InstallationAccessMode.LEGACY_DIRECT,
            legacyStoragePermissionGranted = true,
        )

    @Test
    fun catalogShowsInstalledAndAvailableVersionsAndUpdateAction() {
        val latest = ReleaseAsset(22, "bundle.zip", 100_000_000, "2026.11")
        launch(
            approved()
                .copy(
                    rememberedFgo = GameProfile.BETTER_NA,
                    catalog =
                        mapOf(
                            GameProfile.JP to ProfileSummary(latest = latest),
                            GameProfile.BETTER_NA to
                                ProfileSummary(
                                    installedVersion = "2026.10",
                                    receiptStatus = ReceiptStatus.INSTALLED,
                                    latest = latest,
                                    installedAssetId = 21,
                                ),
                        ),
                )
        )
        compose.onNodeWithText("Atualizar tradução").assertIsDisplayed()
        compose
            .onNodeWithText("NA · BetterFGO: Instalada 2026.10 · atualizar para 2026.11")
            .performScrollTo()
            .assertIsDisplayed()
        screenshot("10-catalogo-status")
        openFgo()
        compose.onNodeWithText("Baixar tradução").assertIsEnabled()
        compose.onNodeWithText("Qual versão você joga?").assertDoesNotExist()
        compose.onNodeWithText("BetterFGO", substring = false).assertIsDisplayed()
        compose.onNodeWithText("100,0 MB").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun rememberedDestinationCanStillBeChanged() {
        launch(approved().copy(rememberedFgo = GameProfile.BETTER_NA))
        openFgo()
        compose.onNodeWithText("Alterar versão do jogo").performScrollTo().performClick()
        compose.onNodeWithText("Qual versão você joga?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("América do Norte").assertIsSelected()
        compose.onNodeWithText("BetterFGO", substring = false).assertIsSelected()
    }

    @Test
    fun errorDiagnosticCopiesUsefulSummaryWithoutPrivatePaths() {
        launch(approved())
        chooseVersion()
        compose.runOnIdle { ui = ui.copy(failureCode = BundleFailureCode.NETWORK_UNAVAILABLE) }
        compose.onNodeWithText("Detalhes técnicos").performScrollTo().performClick()
        compose.onNodeWithText("Copiar diagnóstico").performScrollTo().performClick()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard =
                context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text.toString()
            assertTrue(text.contains("NETWORK_UNAVAILABLE"))
            assertTrue(text.contains("fgo-na"))
            assertTrue(!text.contains(context.filesDir.absolutePath))
        }
    }

    @Test
    fun catalogAndAccessHaveOneClearAction() {
        launch()
        screenshot("01-catalogo")
        openFgo()
        compose.onNodeWithText("Prepare o acesso").assertIsDisplayed()
        compose.onNodeWithText("Verificar acesso").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("América do Norte").assertDoesNotExist()
        compose.onNodeWithText("Baixar tradução").assertDoesNotExist()
        screenshot("02-acesso-shizuku")
    }

    @Test
    fun authorizationUnlocksRegionAndBetterFgoDestination() {
        launch()
        openFgo()
        compose.onNodeWithText("Verificar acesso").performClick()
        compose.onNodeWithText("Qual versão você joga?").assertIsDisplayed()
        compose.onNodeWithText("Continuar").assertIsNotEnabled()
        compose.onNodeWithText("América do Norte").performClick()
        compose.onNodeWithText("BetterFGO", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GameProfile.BETTER_NA, ui.selectedProfile) }
        screenshot("03-versao-fgo")
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Baixar tradução").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
        screenshot("04-revisao")
    }

    @Test
    fun blueArchiveShowsOnlyGlobalAndNoBetterFgo() {
        launch(approved())
        compose
            .onNodeWithContentDescription("Blue Archive, Servidor Global. Selecionar jogo")
            .performClick()
        compose.onNodeWithText("Global", substring = false).performClick()
        compose.onNodeWithText("BetterFGO", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Baixar tradução").assertIsEnabled()
        screenshot("05-blue-archive")
    }

    @Test
    fun backMovesThroughStepsBeforeReturningToCatalog() {
        launch(approved())
        chooseVersion()
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithText("Qual versão você joga?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithText("Prepare o acesso").assertIsDisplayed()
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithContentDescription("Ajuda").assertIsDisplayed()
    }

    @Test
    fun losingAccessReturnsToAccessAndRemovesInstallControls() {
        launch(approved())
        chooseVersion()
        compose.runOnIdle {
            ui = ui.copy(legacyStoragePermissionGranted = false, selectedProfile = null)
        }
        compose.onNodeWithText("Prepare o acesso").assertIsDisplayed()
        compose.onNodeWithText("Baixar tradução").assertDoesNotExist()
        compose.onNodeWithText("Verificar acesso").assertIsEnabled()
    }

    @Test
    fun downloadingLocksNavigationAndCommitCannotBeCancelled() {
        launch(approved())
        chooseVersion()
        compose.runOnIdle {
            ui =
                ui.copy(
                    isWorking = true,
                    currentPhase = OperationPhase.DOWNLOAD,
                    progress = .42f,
                    backgroundDownload =
                        DownloadSnapshot(
                            profile = GameProfile.NA,
                            asset = ReleaseAsset(10, "bundle.zip", 100_000_000, "2026.11"),
                            status = DownloadStatus.DOWNLOADING,
                            bytes = 42_000_000,
                            bytesPerSecond = 2_000_000.0,
                            remainingSeconds = 29,
                        ),
                )
        }
        compose.onNodeWithContentDescription("Voltar").assertIsNotEnabled()
        compose.onNodeWithText("Cancelar operação").assertIsDisplayed()
        compose.onNodeWithText("Baixando a tradução").assertIsDisplayed()
        compose.onNodeWithText("42,0 MB / 100,0 MB").performScrollTo().assertIsDisplayed()
        screenshot("06-download")
        compose.runOnIdle { ui = ui.copy(currentPhase = OperationPhase.COMMIT) }
        compose.onNodeWithText("Cancelar operação").assertDoesNotExist()
        compose.onNodeWithContentDescription("Voltar").assertIsNotEnabled()
    }

    @Test
    fun installRequiresClosedGameAndCapabilitiesIncludingUpdates() {
        launch(approved())
        chooseVersion()
        compose.runOnIdle {
            ui =
                ui.copy(
                    importedBundle = bundle(),
                    installerCapabilities = InstallerCapabilities(true, "OK"),
                )
        }
        compose.onNode(hasText("Instalar tradução") and hasClickAction()).assertIsNotEnabled()
        compose
            .onNodeWithText("Fechei completamente o Fate/Grand Order")
            .performScrollTo()
            .performClick()
        compose.onNode(hasText("Instalar tradução") and hasClickAction()).assertIsEnabled()
        // A previously installed version must not hide the action to install an update.
        compose.runOnIdle {
            ui = ui.copy(isInstalled = true, installationReceipt = receipt(ReceiptStatus.INSTALLED))
        }
        compose
            .onNode(hasText("Instalar tradução") and hasClickAction())
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(1, installs) }
        screenshot("07-instalar")
        compose.runOnIdle {
            ui =
                ui.copy(
                    installerCapabilities = InstallerCapabilities(false, "GAME_DIRECTORY_NOT_FOUND")
                )
        }
        compose.onNode(hasText("Instalar tradução") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun installedTranslationCanUpdateAndRemovalNeedsConfirmation() {
        launch(approved())
        chooseVersion()
        compose.runOnIdle {
            ui = ui.copy(isInstalled = true, installationReceipt = receipt(ReceiptStatus.INSTALLED))
        }
        compose.onNodeWithText("Tudo pronto para jogar").assertIsDisplayed()
        screenshot("08-concluido")
        compose.onNodeWithText("Buscar atualização da tradução").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
        compose.onNodeWithText("Gerenciar tradução").performScrollTo().performClick()
        compose
            .onNodeWithText("Desinstalar tradução", substring = false)
            .performScrollTo()
            .performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithText("Cancelar", substring = false).performClick()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithText("Desinstalar tradução", substring = false).performClick()
        compose.onNodeWithText("Desinstalar", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    @Test
    fun interruptedReceiptCannotInstallAndNetworkFailureCanRetry() {
        launch(approved())
        chooseVersion()
        compose.runOnIdle { ui = ui.copy(failureCode = BundleFailureCode.NETWORK_UNAVAILABLE) }
        compose.onNodeWithText("Tentar baixar novamente").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, downloads) }
        screenshot("09-falha-download")
        compose.runOnIdle {
            ui =
                ui.copy(
                    failureCode = null,
                    importedBundle = bundle(),
                    installerCapabilities = InstallerCapabilities(true, "OK"),
                    gameClosedConfirmed = true,
                    installationReceipt = receipt(ReceiptStatus.PENDING),
                )
        }
        compose.onNode(hasText("Instalar tradução") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("Revisar instalação interrompida").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, installs)
            assertEquals(0, removals)
        }
        compose.onNodeWithText("Desinstalar tradução?").assertIsDisplayed()
    }

    private fun receipt(status: ReceiptStatus) =
        InstallationReceipt(GameProfile.NA, "2026.11", status, emptyList())

    private fun bundle(): RemoteImportedBundle {
        val manifest =
            LocalBundleManifest(
                1,
                "test",
                "fgo-na",
                "fgo-na",
                GameProfile.NA.packageName,
                GameProfile.NA.destinationRelativeDirectory,
                "2026.11",
                "2026-11-14",
                1,
                64,
                emptyList(),
            )
        val file =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "ui-fixture.zip",
            )
        val verified = VerifiedBundle(manifest, file, file.parentFile!!)
        return RemoteImportedBundle(
            ImportedBundle(verified, "test", "fixture"),
            ReleaseAsset(1, "fixture.zip", 64, "2026.11"),
        )
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // The Compose test clock does not drive the Android compositor's real vsync.
        Thread.sleep(250)
        val directory =
            File(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getExternalFilesDir(null),
                "screenshots",
            )
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
