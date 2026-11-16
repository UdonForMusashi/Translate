package dev.translate.installer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.translate.installer.BuildConfig

/** No paths, account information, URLs, credentials, or unrestricted exception messages. */
internal fun diagnosticSummary(state: ImportUiState, androidVersion: String, sdk: Int): String =
    buildString {
            appendLine("Fate Café ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android $androidVersion · API $sdk")
            appendLine("Perfil: ${state.selectedProfile?.profileId ?: "não selecionado"}")
            appendLine(
                "Acesso: ${state.accessMode.name}; autorizado: ${state.installationAccessApproved}"
            )
            appendLine(
                "Shizuku: ${state.shizukuGate.status.name}; API: ${state.shizukuGate.serverApiVersion ?: "—"}"
            )
            appendLine("Etapa: ${state.currentPhase?.name ?: "nenhuma"}")
            appendLine("Erro do pacote: ${state.failureCode?.name ?: "nenhum"}")
            appendLine(
                "Erro da instalação: ${state.installerFailureCode ?: state.installerCapabilities?.reasonCode ?: "nenhum"}"
            )
            appendLine(
                "Download: ${state.backgroundDownload.status.name}; bytes: ${state.backgroundDownload.bytes}"
            )
            appendLine("Últimas operações:")
            state.events.takeLast(10).forEach {
                appendLine("${it.phase.name} / ${it.status.name} / ${it.messageCode.name}")
            }
        }
        .trim()

@Composable
internal fun DiagnosticCopyButton(state: ImportUiState) {
    val context = LocalContext.current
    TextButton({
        val text = diagnosticSummary(state, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            ClipData.newPlainText("Diagnóstico Fate Café", text)
        )
        Toast.makeText(context, "Diagnóstico copiado", Toast.LENGTH_SHORT).show()
    }) {
        Text("Copiar diagnóstico")
    }
}
