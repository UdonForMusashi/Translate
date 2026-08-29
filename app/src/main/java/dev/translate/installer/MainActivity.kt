package dev.translate.installer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import dev.translate.installer.audit.MessageCode
import dev.translate.installer.audit.OperationEvent
import dev.translate.installer.audit.OperationStatus
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.installer.ReceiptStatus
import dev.translate.installer.security.BundleFailureCode
import dev.translate.installer.shizuku.ShizukuGateState
import dev.translate.installer.shizuku.ShizukuGateStatus
import dev.translate.installer.shizuku.ShizukuServiceIdentity
import dev.translate.installer.ui.ImportUiState
import dev.translate.installer.ui.ImportViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var model: ImportViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ImportViewModel::class.java]
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by model.state.collectAsState()
                    InstallerScreen(
                        state = state,
                        onVerifyShizuku = model::verifyShizuku,
                        onProfileSelected = model::selectProfile,
                        onBundleSelected = model::importBundle,
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
            model.revalidateShizuku()
        }
    }
}

@Composable
private fun InstallerScreen(
    state: ImportUiState,
    onVerifyShizuku: () -> Unit,
    onProfileSelected: (GameProfile) -> Unit,
    onBundleSelected: (android.net.Uri) -> Unit,
    onCancelImport: () -> Unit,
    onGameClosedChanged: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(true) }
    val selectZip = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> if (uri != null) onBundleSelected(uri) },
    )

    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Translation Installer",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Primeiro verifique o Shizuku. Depois escolha a versão do jogo e o ZIP.",
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionCard(title = "1. Conexão com Shizuku") {
                Text(
                    "O Shizuku é um aplicativo separado: o usuário precisa baixá-lo, iniciá-lo e autorizar este instalador.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ShizukuGateContent(
                    gate = state.shizukuGate,
                    isWorking = state.isWorking,
                    onVerify = onVerifyShizuku,
                )
            }

            SectionCard(title = "2. Versão do jogo") {
                GameProfile.entries.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = state.shizukuGate.isApproved && !state.isWorking,
                            ) {
                                onProfileSelected(profile)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.selectedProfile == profile,
                            onClick = { onProfileSelected(profile) },
                            enabled = state.shizukuGate.isApproved && !state.isWorking,
                        )
                        Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (!state.shizukuGate.isApproved) {
                    Text(
                        text = "Verifique e autorize o Shizuku para liberar esta etapa.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SectionCard(title = "3. Pacote local") {
                Button(
                    onClick = {
                        selectZip.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    enabled = state.shizukuGate.isApproved &&
                        state.selectedProfile != null &&
                        !state.isWorking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Selecionar pacote ZIP")
                }
                Text(
                    text = "O seletor do Android pode abrir Downloads. O arquivo original não é alterado.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(title = "4. Progresso") {
                val currentPhase = state.currentPhase
                Text(
                    text = currentPhase?.name ?: "AGUARDANDO",
                    fontWeight = FontWeight.SemiBold,
                )
                when {
                    state.isWorking && state.progress == null -> CircularProgressIndicator()
                    state.progress != null -> LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.isWorking &&
                    state.currentPhase != dev.translate.installer.audit.OperationPhase.COMMIT &&
                    state.currentPhase != dev.translate.installer.audit.OperationPhase.CLEANUP
                ) {
                    OutlinedButton(
                        onClick = onCancelImport,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancelar operação")
                    }
                }
                state.failureCode?.let { code ->
                    Text(
                        text = failureMessage(code),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.importedBundle?.let { imported ->
                    Text(
                        text = "Pacote ${imported.verifiedBundle.manifest.version} autenticado e descompactado (${imported.verifiedBundle.manifest.fileCount} arquivos).",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                state.installerCapabilities?.let { capabilities ->
                    Text(
                        text = if (capabilities.available) {
                            "Acesso ao jogo e espaço livre necessário foram confirmados."
                        } else {
                            installerFailureMessage(capabilities.reasonCode)
                        },
                        color = if (capabilities.available) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                state.installerFailureCode
                    ?.takeUnless { it == state.installerCapabilities?.reasonCode }
                    ?.let { code ->
                    Text(installerFailureMessage(code), color = MaterialTheme.colorScheme.error)
                }
                if (state.isInstalled) {
                    Text(
                        "Tradução instalada e verificada. O recibo de desinstalação está salvo somente no diretório privado do aplicativo.",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (state.importedBundle != null || state.installationReceipt != null) {
                SectionCard(title = "5. Instalação") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.isWorking) {
                                onGameClosedChanged(!state.gameClosedConfirmed)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = state.gameClosedConfirmed,
                            onCheckedChange = onGameClosedChanged,
                            enabled = !state.isWorking,
                        )
                        Text("Confirmo que fechei completamente o jogo")
                    }
                    if (state.importedBundle != null) {
                        Text(
                            "A instalação altera diretamente somente os arquivos autenticados. Não cria backup, journal ou staging dentro do jogo. Arquivos .bin assinados podem ser adicionados quando ainda não existirem.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = onInstall,
                            enabled = state.shizukuGate.isApproved &&
                                state.installerCapabilities?.available == true &&
                                state.gameClosedConfirmed && !state.isWorking &&
                                state.installationReceipt?.status != ReceiptStatus.PENDING,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Instalar tradução")
                        }
                    }
                    state.installationReceipt?.let { receipt ->
                        if (receipt.status == ReceiptStatus.PENDING) {
                            Text(
                                "Uma instalação anterior pode ter sido interrompida. Por segurança, desinstale os arquivos reconhecidos antes de tentar instalar novamente.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            "Desinstalar apaga somente arquivos que ainda correspondam exatamente aos hashes registrados para ${receipt.bundleVersion}. Arquivos ausentes ou alterados pelo jogo são preservados. O jogo deverá baixar novamente os removidos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(
                            onClick = onUninstall,
                            enabled = state.shizukuGate.isApproved &&
                                state.gameClosedConfirmed && !state.isWorking,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Desinstalar tradução")
                        }
                    }
                }
            }

            SectionCard(title = "Detalhes técnicos") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Mostrar operações ao vivo")
                    Switch(
                        checked = showDetails,
                        onCheckedChange = { showDetails = it },
                    )
                }
                if (showDetails) {
                    if (state.events.isEmpty()) {
                        Text("Nenhuma operação iniciada.")
                    } else {
                        state.events.forEachIndexed { index, event ->
                            if (index > 0) HorizontalDivider()
                            EventRow(event)
                        }
                    }
                }
            }

            Text(
                text = "O Shizuku deve estar instalado, iniciado e autorizado durante toda a operação.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ShizukuGateContent(
    gate: ShizukuGateState,
    isWorking: Boolean,
    onVerify: () -> Unit,
) {
    val isBusy = gate.status == ShizukuGateStatus.CHECKING ||
        gate.status == ShizukuGateStatus.PERMISSION_REQUESTING
    Text(
        text = shizukuStatusMessage(gate),
        color = when (gate.status) {
            ShizukuGateStatus.READY -> MaterialTheme.colorScheme.primary
            ShizukuGateStatus.SERVICE_UNAVAILABLE,
            ShizukuGateStatus.PERMISSION_DENIED,
            ShizukuGateStatus.VERSION_UNSUPPORTED,
            ShizukuGateStatus.IDENTITY_UNTRUSTED,
            ShizukuGateStatus.ERROR,
            -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
        fontWeight = FontWeight.SemiBold,
    )
    if (isBusy) {
        CircularProgressIndicator()
    }
    Button(
        onClick = onVerify,
        enabled = !isBusy && !isWorking && !gate.isApproved,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(shizukuButtonLabel(gate.status))
    }
    Text(
        text = "Esta verificação apenas confirma serviço, permissão e versão. Nenhum arquivo do jogo é acessado.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun EventRow(event: OperationEvent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = "%04d  %-12s  %s".format(
                Locale.ROOT,
                event.sequence,
                event.phase.name,
                statusLabel(event.status),
            ),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = eventMessage(event.messageCode),
            style = MaterialTheme.typography.bodySmall,
        )
        event.fileRelativePath?.let { path ->
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (event.bytesProcessed != null) {
            val total = event.bytesTotal?.let { " / ${formatBytes(it)}" }.orEmpty()
            Text(
                text = formatBytes(event.bytesProcessed) + total,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        event.failureCode?.let { code ->
            Text(
                text = "Código: $code",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun eventMessage(code: MessageCode): String = when (code) {
    MessageCode.PROFILE_SELECTED -> "Perfil selecionado"
    MessageCode.SOURCE_COPY_STARTED -> "Cópia para o staging privado iniciada"
    MessageCode.SOURCE_COPY_PROGRESS -> "Copiando pacote"
    MessageCode.SOURCE_COPY_SUCCEEDED -> "Cópia privada concluída"
    MessageCode.SIGNATURE_CHECK_STARTED -> "Verificando assinatura do manifesto"
    MessageCode.SIGNATURE_CHECK_SUCCEEDED -> "Assinatura válida"
    MessageCode.ARCHIVE_INSPECTION_STARTED -> "Inspecionando estrutura e manifesto"
    MessageCode.ARCHIVE_INSPECTION_SUCCEEDED -> "Estrutura do ZIP válida"
    MessageCode.FILE_EXTRACTION_STARTED -> "Extração privada iniciada"
    MessageCode.FILE_EXTRACTION_PROGRESS -> "Extraindo e calculando SHA-256"
    MessageCode.FILE_EXTRACTION_SUCCEEDED -> "Extração e hashes concluídos"
    MessageCode.BUNDLE_READY -> "Pacote pronto para o pré-check de instalação"
    MessageCode.PRIVILEGED_PROBE_STARTED -> "Testando acesso pelo serviço Shizuku"
    MessageCode.PRIVILEGED_PROBE_SUCCEEDED -> "Diretório do jogo acessível"
    MessageCode.COMMIT_PROGRESS -> "Substituindo e verificando arquivo"
    MessageCode.INSTALLATION_SUCCEEDED -> "Instalação e verificação concluídas"
    MessageCode.UNINSTALL_PROGRESS -> "Verificando hash e removendo arquivo instalado"
    MessageCode.UNINSTALL_SUCCEEDED -> "Tradução desinstalada; o jogo poderá baixar os originais"
    MessageCode.OPERATION_FAILED -> "Operação interrompida com segurança"
    MessageCode.OPERATION_CANCELLED -> "Operação cancelada pelo usuário; staging removido"
}

private fun shizukuStatusMessage(gate: ShizukuGateState): String = when (gate.status) {
    ShizukuGateStatus.CHECK_REQUIRED -> "Verificação obrigatória ainda não realizada."
    ShizukuGateStatus.CHECKING -> "Verificando o Binder e a autorização do Shizuku…"
    ShizukuGateStatus.SERVICE_UNAVAILABLE ->
        "Shizuku indisponível. Instale/inicie o serviço e tente novamente."
    ShizukuGateStatus.PERMISSION_REQUIRED ->
        "Serviço encontrado. É necessário autorizar este aplicativo."
    ShizukuGateStatus.PERMISSION_REQUESTING ->
        "Aguardando sua resposta na tela de autorização do Shizuku…"
    ShizukuGateStatus.PERMISSION_DENIED ->
        "Autorização negada. Libere o aplicativo dentro do Shizuku e tente novamente."
    ShizukuGateStatus.VERSION_UNSUPPORTED ->
        "Versão incompatível do serviço (API ${gate.serverApiVersion ?: "desconhecida"}); é necessária API 13 ou superior."
    ShizukuGateStatus.IDENTITY_UNTRUSTED ->
        "O serviço retornou uma identidade inesperada e foi recusado."
    ShizukuGateStatus.READY ->
        "Shizuku conectado e autorizado (API ${gate.serverApiVersion}, ${identityLabel(gate.serviceIdentity)})."
    ShizukuGateStatus.ERROR ->
        "Não foi possível verificar o Shizuku com segurança. Tente reiniciar o serviço."
}

private fun identityLabel(identity: ShizukuServiceIdentity?): String = when (identity) {
    ShizukuServiceIdentity.SHELL -> "shell"
    ShizukuServiceIdentity.ROOT -> "root"
    null -> "identidade desconhecida"
}

private fun shizukuButtonLabel(status: ShizukuGateStatus): String = when (status) {
    ShizukuGateStatus.PERMISSION_REQUIRED -> "Solicitar autorização"
    ShizukuGateStatus.CHECKING -> "Verificando…"
    ShizukuGateStatus.PERMISSION_REQUESTING -> "Aguardando autorização…"
    ShizukuGateStatus.READY -> "Conexão verificada"
    else -> "Verificar conexão com Shizuku"
}

private fun failureMessage(code: BundleFailureCode): String = when (code) {
    BundleFailureCode.SOURCE_PERMISSION_DENIED ->
        "O Android recusou a leitura do ZIP. Verifique as permissões do arquivo em Downloads e selecione-o novamente."
    BundleFailureCode.SOURCE_READ_FAILED ->
        "O provedor de arquivos não conseguiu ler o ZIP. Copie ou baixe o arquivo novamente para Downloads."
    BundleFailureCode.SIGNING_KEY_UNKNOWN ->
        "A chave pública de release ainda não foi configurada. O app recusou o pacote."
    BundleFailureCode.PROFILE_MISMATCH ->
        "O ZIP assinado pertence a outro perfil. Confira se escolheu JP ou NA corretamente."
    BundleFailureCode.SIGNATURE_INVALID -> "A assinatura do manifesto é inválida."
    BundleFailureCode.ARCHIVE_TOO_LARGE -> "O ZIP excede o limite local de segurança."
    BundleFailureCode.FILE_HASH_MISMATCH -> "Um arquivo não corresponde ao SHA-256 assinado."
    BundleFailureCode.INSUFFICIENT_STORAGE ->
        "Não há espaço livre suficiente para concluir a operação com segurança."
    else -> "O pacote foi recusado com o código ${code.name}."
}

private fun installerFailureMessage(code: String): String = when (code) {
    "GAME_DIRECTORY_NOT_FOUND" ->
        "O diretório do jogo selecionado não foi encontrado. Abra o jogo ao menos uma vez e confirme JP/NA."
    "GAME_DIRECTORY_NOT_WRITABLE" ->
        "A identidade atual do Shizuku não consegue escrever no diretório do jogo. Este aparelho pode exigir root/Sui."
    "UNSAFE_GAME_DIRECTORY", "EXTRACTED_SOURCE_UNSAFE" ->
        "Um caminho ou link inseguro foi detectado; a instalação foi bloqueada."
    "SHIZUKU_PERMISSION_DENIED" -> "A autorização do Shizuku foi revogada."
    "SERVICE_BIND_FAILED", "SERVICE_DISCONNECTED" ->
        "O serviço Shizuku desconectou. Inicie-o novamente e refaça a verificação."
    "FILE_SIZE_MISMATCH", "FILE_HASH_MISMATCH" ->
        "Um arquivo não corresponde ao conteúdo autenticado esperado."
    "INSUFFICIENT_STORAGE" ->
        "Não há espaço livre suficiente no armazenamento do jogo para instalar com segurança."
    "TARGET_FILE_MISSING" ->
        "Um arquivo obrigatório que não é .bin ainda não existe no jogo. Abra-o para concluir os downloads e tente novamente."
    "TARGET_CHANGED" ->
        "Um arquivo mudou desde a instalação e foi preservado."
    "RECEIPT_INVALID" ->
        "O recibo privado de instalação é inválido; a exclusão foi bloqueada por segurança."
    "RECEIPT_WRITE_FAILED" ->
        "Não foi possível salvar o recibo privado antes da instalação; nenhum arquivo deve ser alterado."
    "RECEIPT_DELETE_FAILED" ->
        "Os arquivos foram removidos, mas não foi possível apagar o recibo privado. Tente desinstalar novamente."
    else -> "A instalação foi recusada com o código $code."
}

private fun statusLabel(status: OperationStatus): String = when (status) {
    OperationStatus.STARTED -> "INÍCIO"
    OperationStatus.PROGRESS -> "PROGRESSO"
    OperationStatus.SUCCEEDED -> "OK"
    OperationStatus.FAILED -> "FALHA"
    OperationStatus.SKIPPED -> "IGNORADO"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var index = -1
    do {
        value /= 1024.0
        index++
    } while (value >= 1024 && index < units.lastIndex)
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}
