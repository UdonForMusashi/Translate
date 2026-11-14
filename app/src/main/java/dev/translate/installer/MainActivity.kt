package dev.translate.installer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import dev.translate.installer.installer.InstallationAccessMode
import dev.translate.installer.ui.FateCafeApp
import dev.translate.installer.ui.ImportViewModel
import dev.translate.installer.ui.theme.FateCafeTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: ImportViewModel
    private lateinit var legacyStoragePermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ImportViewModel::class.java]
        legacyStoragePermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
                model::updateLegacyStoragePermission,
            )
        setContent {
            FateCafeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by model.state.collectAsState()
                    FateCafeApp(
                        state = state,
                        onVerifyAccess = ::verifyInstallationAccess,
                        onProfileSelected = model::selectProfile,
                        onBetterFgoChanged = model::setBetterFgoEnabled,
                        onClearProfileSelection = model::clearProfileSelection,
                        onResetCompletedInstallation = model::resetCompletedInstallationUi,
                        onDownloadLatest = model::downloadLatestBundle,
                        onCancelImport = model::cancelImport,
                        onGameClosedChanged = model::setGameClosedConfirmed,
                        onInstall = model::installImportedBundle,
                        onUninstall = model::uninstallTranslation,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) {
            when (model.state.value.accessMode) {
                InstallationAccessMode.LEGACY_DIRECT ->
                    model.updateLegacyStoragePermission(
                        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                InstallationAccessMode.SHIZUKU -> model.revalidateShizuku()
            }
        }
    }

    private fun verifyInstallationAccess() {
        when (model.state.value.accessMode) {
            InstallationAccessMode.LEGACY_DIRECT ->
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            InstallationAccessMode.SHIZUKU -> model.verifyShizuku()
        }
    }
}
