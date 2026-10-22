package dev.translate.installer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.translate.installer.ui.theme.BaristaLatteTheme
import dev.translate.installer.ui.theme.CafeBackground
import dev.translate.installer.ui.theme.CafeLine
import dev.translate.installer.ui.theme.CafeMuted
import dev.translate.installer.ui.theme.CafeSage
import dev.translate.installer.ui.theme.CafeSurface
import dev.translate.installer.ui.theme.CafeSurfaceRaised
import dev.translate.installer.ui.theme.Espresso
import dev.translate.installer.ui.theme.LatteCream
import dev.translate.installer.ui.theme.LatteGold
import dev.translate.installer.ui.theme.LatteGoldBright
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var model: ImportViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ImportViewModel::class.java]
        setContent {
            BaristaLatteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by model.state.collectAsState()
                    BaristaLatteApp(
                        state = state,
                        onVerifyShizuku = model::verifyShizuku,
                        onProfileSelected = model::selectProfile,
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
            model.revalidateShizuku()
        }
    }
}

private const val FATE_GRAND_ORDER_DESTINATION = "fate-grand-order"

@Composable
private fun BaristaLatteApp(
    state: ImportUiState,
    onVerifyShizuku: () -> Unit,
    onProfileSelected: (GameProfile) -> Unit,
    onDownloadLatest: () -> Unit,
    onCancelImport: () -> Unit,
    onGameClosedChanged: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf<String?>(null) }

    if (destination == FATE_GRAND_ORDER_DESTINATION) {
        InstallerScreen(
            state = state,
            onBackToCatalog = { if (!state.isWorking) destination = null },
            onVerifyShizuku = onVerifyShizuku,
            onProfileSelected = onProfileSelected,
            onDownloadLatest = onDownloadLatest,
            onCancelImport = onCancelImport,
            onGameClosedChanged = onGameClosedChanged,
            onInstall = onInstall,
            onUninstall = onUninstall,
        )
    } else {
        GameCatalogScreen(
            onFateGrandOrderSelected = { destination = FATE_GRAND_ORDER_DESTINATION },
        )
    }
}

@Composable
private fun GameCatalogScreen(
    onFateGrandOrderSelected: () -> Unit,
) {
    Scaffold(containerColor = Espresso) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(CafeBackground, Espresso, Espresso),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                BrandHeader()

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "TRADUÇÕES DISPONÍVEIS",
                    color = LatteGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = "Escolha seu jogo",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = "Baixe o pacote mais recente e instale a tradução diretamente no Android.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = CafeMuted,
                )

                CatalogGameCard(
                    marker = "FGO",
                    title = "Fate/Grand Order",
                    description = "Tradução feita pela comunidade Fate Café, disponível para os servidores Japão e América do Norte.",
                    status = "DISPONÍVEL",
                    accent = LatteGold,
                    enabled = true,
                    onClick = onFateGrandOrderSelected,
                )
                CatalogGameCard(
                    marker = "BA",
                    title = "Blue Archive",
                    description = "A tradução Global está sendo preparada e será liberada em breve.",
                    status = "EM BREVE",
                    accent = CafeSage,
                    enabled = false,
                    onClick = {},
                )

                Text(
                    text = "Barista Latte • Traduções PT-BR",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = CafeMuted.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun BrandHeader(compact: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier.size(if (compact) 46.dp else 58.dp),
            shape = CircleShape,
            color = LatteGold.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, LatteGold.copy(alpha = 0.42f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "BL",
                    color = LatteGoldBright,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 16.sp else 19.sp,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "BARISTA LATTE",
                color = LatteCream,
                fontSize = if (compact) 15.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
            )
            Text(
                text = if (compact) "Instalador de traduções" else "Traduções servidas com cuidado",
                style = MaterialTheme.typography.bodySmall,
                color = CafeMuted,
            )
        }
    }
}

@Composable
private fun CatalogGameCard(
    marker: String,
    title: String,
    description: String,
    status: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.62f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.42f else 0.22f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.14f), CafeSurface, CafeSurface),
                    ),
                )
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = accent.copy(alpha = 0.13f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = marker,
                                color = accent,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = accent.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = status,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            color = accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = LatteCream,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CafeMuted,
                )
                if (enabled) {
                    Text(
                        text = "Selecionar jogo  →",
                        color = accent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallerScreen(
    state: ImportUiState,
    onBackToCatalog: () -> Unit,
    onVerifyShizuku: () -> Unit,
    onProfileSelected: (GameProfile) -> Unit,
    onDownloadLatest: () -> Unit,
    onCancelImport: () -> Unit,
    onGameClosedChanged: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (!state.isWorking) onBackToCatalog()
    }

    Scaffold(containerColor = Espresso) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = onBackToCatalog,
                enabled = !state.isWorking,
            ) {
                Text("←  Todos os jogos")
            }
            BrandHeader(compact = true)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Fate/Grand Order",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Conecte o Shizuku, escolha sua região e baixe a tradução mais recente.",
                style = MaterialTheme.typography.bodyMedium,
                color = CafeMuted,
            )

            SectionCard(title = "Conexão com Shizuku", step = "01") {
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

            SectionCard(title = "Região do jogo", step = "02") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(GameProfile.JP, GameProfile.NA).forEach { profile ->
                        RegionOption(
                            profile = profile,
                            selected = state.selectedProfile == profile,
                            enabled = state.shizukuGate.isApproved && !state.isWorking,
                            onSelected = { onProfileSelected(profile) },
                        )
                    }
                }
                if (!state.shizukuGate.isApproved) {
                    Text(
                        text = "Verifique e autorize o Shizuku para liberar esta etapa.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SectionCard(title = "Pacote oficial", step = "03") {
                Button(
                    onClick = onDownloadLatest,
                    enabled = state.shizukuGate.isApproved &&
                        state.selectedProfile != null &&
                        !state.isWorking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Baixar pacote mais recente")
                }
                Text(
                    text = "O aplicativo aceita pacotes somente pela última Release pública do perfil selecionado. Antes de cada instalação, o GitHub é consultado novamente e um cache desatualizado é substituído. Após uma instalação concluída, o ZIP e os arquivos temporários são apagados automaticamente.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(title = "Progresso", step = "04") {
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
                SectionCard(title = "Instalação", step = "05") {
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
                        Text("Para instalar, confirmo que fechei completamente o jogo")
                    }
                    if (state.importedBundle != null) {
                        Text(
                            "A instalação altera diretamente somente os arquivos autenticados do Fate/Grand Order. Não cria backup, journal ou staging dentro do jogo.",
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
                            Text("Verificar e instalar tradução")
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
                            enabled = state.shizukuGate.isApproved && !state.isWorking,
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
                text = "Barista Latte • O Shizuku deve permanecer iniciado e autorizado durante toda a operação.",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = CafeMuted,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RegionOption(
    profile: GameProfile,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
) {
    val accent = if (selected) LatteGold else CafeMuted
    val regionName = when (profile) {
        GameProfile.JP -> "Japão"
        GameProfile.NA -> "América do Norte"
        GameProfile.BA -> "Global"
    }
    val regionCode = when (profile) {
        GameProfile.JP -> "JP"
        GameProfile.NA -> "NA"
        GameProfile.BA -> "GL"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onSelected),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) LatteGold.copy(alpha = 0.10f) else CafeSurfaceRaised,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) LatteGold.copy(alpha = 0.72f) else CafeLine,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = accent.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = regionCode,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = regionName,
                    color = if (enabled) LatteCream else CafeMuted,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (profile == GameProfile.JP) {
                        "Servidor japonês"
                    } else {
                        "Servidor global em inglês"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = CafeMuted,
                )
            }
            RadioButton(
                selected = selected,
                onClick = onSelected,
                enabled = enabled,
            )
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
    step: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CafeSurface),
        border = BorderStroke(1.dp, CafeLine.copy(alpha = 0.9f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (step != null) {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = LatteGold.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = step,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            color = LatteGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
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
    MessageCode.RELEASE_LOOKUP_STARTED -> "Consultando a última Release pública"
    MessageCode.RELEASE_LOOKUP_SUCCEEDED -> "Release mais recente encontrada"
    MessageCode.CACHED_RELEASE_CURRENT -> "O ZIP em cache ainda é a Release mais recente"
    MessageCode.CACHED_RELEASE_STALE -> "O ZIP em cache está desatualizado e será substituído"
    MessageCode.CACHE_CLEAR_STARTED -> "Limpando o cache privado"
    MessageCode.CACHE_CLEAR_SUCCEEDED -> "Cache privado removido"
    MessageCode.CACHE_CLEAR_FAILED -> "A tradução foi instalada, mas a limpeza automática do cache falhou"
    MessageCode.DOWNLOAD_STARTED -> "Download seguro iniciado"
    MessageCode.DOWNLOAD_PROGRESS -> "Baixando pacote"
    MessageCode.DOWNLOAD_SUCCEEDED -> "Download concluído"
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
    BundleFailureCode.NETWORK_UNAVAILABLE ->
        "Não foi possível conectar ao GitHub. Verifique a internet e tente novamente."
    BundleFailureCode.RELEASE_NOT_FOUND ->
        "Ainda não há uma Release publicada para o perfil selecionado."
    BundleFailureCode.RELEASE_RATE_LIMITED ->
        "O GitHub limitou temporariamente as consultas deste aparelho. Tente mais tarde."
    BundleFailureCode.RELEASE_REQUEST_FAILED ->
        "O GitHub recusou a consulta da Release. Tente novamente mais tarde."
    BundleFailureCode.RELEASE_CHANGED_DURING_DOWNLOAD ->
        "A Release mudou repetidamente durante o download. O cache antigo foi apagado; tente novamente."
    BundleFailureCode.RELEASE_RESPONSE_INVALID,
    BundleFailureCode.RELEASE_ASSET_INVALID,
    BundleFailureCode.DOWNLOAD_REDIRECT_INVALID,
    -> "A Release não contém exatamente um ZIP seguro e válido."
    BundleFailureCode.DOWNLOAD_SIZE_MISMATCH ->
        "O tamanho baixado não corresponde ao tamanho publicado; o pacote foi apagado."
    BundleFailureCode.DOWNLOAD_FAILED ->
        "O download não pôde ser concluído com segurança."
    BundleFailureCode.SIGNING_KEY_UNKNOWN ->
        "A chave pública de release ainda não foi configurada. O app recusou o pacote."
    BundleFailureCode.PROFILE_MISMATCH ->
        "O ZIP assinado pertence a outro perfil. Confira o jogo e a região selecionados."
    BundleFailureCode.SIGNATURE_INVALID -> "A assinatura do manifesto é inválida."
    BundleFailureCode.ARCHIVE_TOO_LARGE -> "O ZIP excede o limite local de segurança."
    BundleFailureCode.FILE_HASH_MISMATCH -> "Um arquivo não corresponde ao SHA-256 assinado."
    BundleFailureCode.INSUFFICIENT_STORAGE ->
        "Não há espaço livre suficiente para concluir a operação com segurança."
    else -> "O pacote foi recusado com o código ${code.name}."
}

private fun installerFailureMessage(code: String): String = when (code) {
    "GAME_DIRECTORY_NOT_FOUND" ->
        "O diretório do jogo selecionado não foi encontrado. Abra o jogo ao menos uma vez e confirme o perfil escolhido."
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
        "Um arquivo obrigatório ainda não existe no jogo. Abra o jogo, conclua os downloads e tente novamente."
    "TARGET_CHANGED" ->
        "Um arquivo mudou desde a instalação e foi preservado."
    "RECEIPT_INVALID" ->
        "O recibo privado de instalação é inválido; a exclusão foi bloqueada por segurança."
    "RECEIPT_WRITE_FAILED" ->
        "Não foi possível salvar o recibo privado antes da instalação; nenhum arquivo deve ser alterado."
    "RECEIPT_DELETE_FAILED" ->
        "Os arquivos foram removidos, mas não foi possível apagar o recibo privado. Tente desinstalar novamente."
    "CACHE_CLEAR_FAILED" ->
        "A tradução foi instalada e verificada, mas o cache não pôde ser apagado automaticamente. Ele será removido na próxima operação de download."
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
