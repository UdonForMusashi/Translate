package dev.translate.installer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.installer.InstallationAccessMode
import dev.translate.installer.ui.FateCafeApp
import dev.translate.installer.ui.ImportViewModel
import dev.translate.installer.ui.theme.FateCafeTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: ImportViewModel
    private lateinit var legacyStoragePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var pendingOperation = 0
    private var notificationOpenRequest by mutableIntStateOf(0)
    private var requestedProfile by mutableStateOf<GameProfile?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ImportViewModel::class.java]
        pendingOperation = savedInstanceState?.getInt("pending_operation") ?: 0
        requestedProfile = intent.getStringExtra("download_profile")?.let(GameProfile::fromId)
        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                val operation = pendingOperation
                pendingOperation = 0
                runOperation(operation)
            }
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
                        onDownloadLatest = { startNotifiedOperation(1) },
                        onCancelImport = model::cancelImport,
                        onGameClosedChanged = model::setGameClosedConfirmed,
                        onInstall = { startNotifiedOperation(2) },
                        onUninstall = model::uninstallTranslation,
                        onOpenGame = model::openGame,
                        onRefreshCatalog = { model.refreshCatalog(force = true) },
                        requestedProfile = requestedProfile,
                        notificationOpenRequest = notificationOpenRequest,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedProfile = intent.getStringExtra("download_profile")?.let(GameProfile::fromId)
        notificationOpenRequest++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("pending_operation", pendingOperation)
        super.onSaveInstanceState(outState)
    }

    private fun runOperation(operation: Int) {
        when (operation) {
            1 -> model.downloadLatestBundle()
            2 -> model.installImportedBundle()
        }
    }

    private fun startNotifiedOperation(operation: Int) {
        val settings = getPreferences(MODE_PRIVATE)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED &&
                !settings.getBoolean("notification_prompted", false)
        ) {
            settings.edit().putBoolean("notification_prompted", true).apply()
            pendingOperation = operation
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else runOperation(operation)
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
