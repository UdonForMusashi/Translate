package dev.translate.installer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.translate.installer.audit.MessageCode
import dev.translate.installer.audit.OperationPhase
import dev.translate.installer.data.remote.*
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.installer.InstallationAccessMode
import dev.translate.installer.installer.ReceiptStatus
import dev.translate.installer.shizuku.ShizukuGateStatus
import dev.translate.installer.ui.theme.*

@Composable
internal fun InstallerScreen(
    state: ImportUiState,
    game: TranslationGame,
    onBackToCatalog: () -> Unit,
    onVerifyAccess: () -> Unit,
    onProfileSelected: (GameProfile) -> Unit,
    onBetterFgoChanged: (Boolean) -> Unit,
    onResetCompletedInstallation: () -> Unit,
    onDownloadLatest: () -> Unit,
    onCancelImport: () -> Unit,
    onGameClosedChanged: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    var step by rememberSaveable {
        mutableIntStateOf(
            if (!state.installationAccessApproved) 0
            else if (state.selectedProfile != null) 2 else 1
        )
    }
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    val selected = state.selectedProfile?.takeIf { it.bundleProfile in game.profiles }
    val approved = state.installationAccessApproved
    val gateBusy =
        state.shizukuGate.status in
            setOf(ShizukuGateStatus.CHECKING, ShizukuGateStatus.PERMISSION_REQUESTING)
    val installed =
        selected != null &&
            state.isInstalled &&
            state.installationReceipt?.status == ReceiptStatus.INSTALLED
    val scroll = rememberScrollState()

    // A revoked permission must never leave controls for later steps on screen.
    LaunchedEffect(approved) {
        step =
            if (!approved) 0
            else if (
                state.selectedProfile != null &&
                    state.selectedProfile in
                        listOfNotNull(state.rememberedFgo, state.rememberedBlueArchive)
            )
                2
            else maxOf(1, step)
    }
    LaunchedEffect(step) { scroll.scrollTo(0) }
    LaunchedEffect(selected, state.isWorking) {
        if (step == 2 && selected == null && !state.isWorking) step = if (approved) 1 else 0
    }
    LaunchedEffect(installed, state.currentPhase, state.isWorking) {
        if (
            installed &&
                !state.isWorking &&
                state.currentPhase == OperationPhase.CLEANUP &&
                state.installerFailureCode == null
        ) {
            onResetCompletedInstallation()
            scroll.scrollTo(0)
        }
    }
    val goBack: () -> Unit = {
        if (!state.isWorking) {
            if (step > 0) step-- else onBackToCatalog()
        }
    }
    BackHandler(onBack = goBack)

    Scaffold(
        containerColor = Espresso,
        bottomBar = {
            Surface(color = Espresso) {
                Column(
                    Modifier.navigationBarsPadding().fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HorizontalDivider(color = CafeLine)
                    Column(
                        Modifier.widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            state.isWorking -> {
                                if (
                                    state.currentPhase != OperationPhase.COMMIT &&
                                        state.currentPhase != OperationPhase.CLEANUP
                                ) {
                                    OutlinedButton(
                                        onCancelImport,
                                        Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text("Cancelar operação")
                                    }
                                } else {
                                    Text(
                                        "Finalizando. Mantenha o aplicativo aberto.",
                                        Modifier.fillMaxWidth(),
                                        color = CafeMuted,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            step == 0 ->
                                PrimaryAction(
                                    if (gateBusy) "Aguardando autorização…"
                                    else if (approved) "Continuar" else "Verificar acesso",
                                    enabled = !gateBusy,
                                ) {
                                    if (approved) step = 1 else onVerifyAccess()
                                }
                            step == 1 ->
                                PrimaryAction("Continuar", enabled = approved && selected != null) {
                                    step = 2
                                }
                            installed && state.importedBundle == null ->
                                PrimaryAction("Voltar aos jogos", onClick = onBackToCatalog)
                            state.installationReceipt?.status == ReceiptStatus.PENDING -> {
                                PrimaryAction("Revisar instalação interrompida") {
                                    confirmRemoval = true
                                }
                            }
                            state.importedBundle != null ->
                                PrimaryAction(
                                    "Instalar tradução",
                                    enabled =
                                        approved &&
                                            selected != null &&
                                            state.installerCapabilities?.available == true &&
                                            state.gameClosedConfirmed &&
                                            state.installationReceipt?.status !=
                                                ReceiptStatus.PENDING,
                                    onClick = onInstall,
                                )
                            else ->
                                PrimaryAction(
                                    if (state.failureCode != null) "Tentar baixar novamente"
                                    else if (
                                        state.backgroundDownload.profile?.bundleProfile ==
                                            selected?.bundleProfile &&
                                            state.backgroundDownload.status !in
                                                setOf(DownloadStatus.IDLE, DownloadStatus.CANCELLED)
                                    )
                                        "Continuar download"
                                    else "Baixar tradução",
                                    enabled = approved && selected != null,
                                    onClick = onDownloadLatest,
                                )
                        }
                        if (step == 1 && selected == null)
                            Text(
                                "Selecione o servidor do seu jogo para continuar.",
                                color = CafeMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        goBack,
                        Modifier.semantics { contentDescription = "Voltar" },
                        enabled = !state.isWorking,
                    ) {
                        CafeGlyph(CafeIcon.BACK)
                    }
                    Text(
                        game.title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("PT-BR", style = MaterialTheme.typography.labelMedium, color = CafeMuted)
                }
                if (step == 0) GameBanner(game)
                StepProgress(step)
                when (step) {
                    0 -> AccessStep(state)
                    1 -> VersionStep(state, game, onProfileSelected, onBetterFgoChanged)
                    else ->
                        InstallStep(
                            state,
                            game,
                            onGameClosedChanged,
                            onChangeVersion = { step = 1 },
                            onRemove = { confirmRemoval = true },
                            onDownloadLatest = onDownloadLatest,
                        )
                }
            }
        }
    }
    if (confirmRemoval && !state.isWorking) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("Desinstalar tradução?") },
            text = {
                Text(
                    "Serão removidos apenas os arquivos que ainda correspondem à tradução instalada em ${selected?.displayName ?: game.title}. Arquivos alterados pelo jogo serão preservados. Ao abrir o jogo, ele precisará baixar novamente os arquivos removidos."
                )
            },
            confirmButton = {
                TextButton(
                    {
                        confirmRemoval = false
                        onUninstall()
                    },
                    enabled = approved && selected != null,
                ) {
                    Text("Desinstalar")
                }
            },
            dismissButton = { TextButton({ confirmRemoval = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun AccessStep(state: ImportUiState) {
    val uri = LocalUriHandler.current
    val legacy = state.accessMode == InstallationAccessMode.LEGACY_DIRECT
    SectionHeading(
        "Prepare o acesso",
        if (legacy) "Seu Android permite o acesso direto aos arquivos do jogo."
        else "O Shizuku permite instalar a tradução na pasta do jogo.",
    )
    if (legacy) {
        Instruction(
            "1",
            "Autorize o armazenamento",
            "Toque em Verificar acesso e permita o acesso solicitado pelo Android.",
        )
        Instruction(
            "2",
            "Continue para seu jogo",
            "Esta permissão é usada na instalação e na desinstalação.",
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Instruction(
                "1",
                "Instale o Shizuku",
                "É um aplicativo gratuito, instalado separadamente.",
            )
            TextButton(
                { uri.openUri("https://shizuku.rikka.app/download/") },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text("Baixar Shizuku")
                Spacer(Modifier.width(8.dp))
                CafeGlyph(CafeIcon.EXTERNAL, Modifier.size(16.dp))
            }
            Instruction(
                "2",
                "Inicie o serviço",
                "Abra o Shizuku e siga as instruções de configuração para seu aparelho.",
            )
            Instruction(
                "3",
                "Autorize o Fate Café",
                "Volte aqui, toque em Verificar acesso e permita a conexão.",
            )
        }
    }
    if (state.installationAccessApproved) {
        Notice("Acesso autorizado", "Tudo pronto para escolher a versão do jogo.")
    } else if (!legacy && state.shizukuGate.status != ShizukuGateStatus.CHECK_REQUIRED) {
        val busy =
            state.shizukuGate.status in
                setOf(ShizukuGateStatus.CHECKING, ShizukuGateStatus.PERMISSION_REQUESTING)
        Notice(
            if (busy) "Conectando ao Shizuku" else "Confira o Shizuku",
            accessMessage(state),
            error = !busy,
            info = busy,
        )
        if (!busy) DiagnosticCopyButton(state)
    }
    if (!legacy)
        ExpandableSection("Precisa de ajuda com o Shizuku?") {
            Text(
                "Mantenha o Shizuku iniciado durante toda a instalação. Se a conexão falhar, abra o Shizuku, inicie o serviço e tente novamente.",
                style = MaterialTheme.typography.bodyMedium,
                color = CafeMuted,
            )
            TextButton({ uri.openUri("https://shizuku.rikka.app/guide/setup/") }) {
                Text("Abrir guia de configuração")
            }
        }
}

private fun accessMessage(state: ImportUiState): String =
    when (state.shizukuGate.status) {
        ShizukuGateStatus.SERVICE_UNAVAILABLE ->
            "Abra o Shizuku e inicie o serviço. Depois, volte e verifique o acesso novamente."
        ShizukuGateStatus.CHECKING -> "Verificando a conexão. Aguarde um momento."
        else -> shizukuStatusMessage(state.shizukuGate)
    }

@Composable
private fun Instruction(number: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(28.dp).background(CafeSurfaceRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = LatteGold, style = MaterialTheme.typography.labelMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, color = CafeMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun VersionStep(
    state: ImportUiState,
    game: TranslationGame,
    onProfileSelected: (GameProfile) -> Unit,
    onBetterFgoChanged: (Boolean) -> Unit,
) {
    val enabled = state.installationAccessApproved && !state.isWorking
    SectionHeading("Qual versão você joga?", "Escolha o mesmo servidor usado no seu jogo.")
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        game.profiles.forEach { profile ->
            ChoiceRow(
                regionName(profile),
                when (profile) {
                    GameProfile.JP -> "JP · Idioma original: japonês"
                    GameProfile.NA -> "NA · Idioma original: inglês"
                    else -> "Global · Aplicativo da Nexon"
                },
                state.selectedProfile?.bundleProfile == profile,
                enabled,
            ) {
                onProfileSelected(profile)
            }
        }
    }
    if (game == TranslationGame.FGO) {
        Text("Aplicativo do jogo", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val hasRegion = state.selectedProfile?.bundleProfile in game.profiles
            ChoiceRow(
                "Fate/Grand Order oficial",
                "Aplicativo original do jogo",
                hasRegion && state.selectedProfile?.isBetterFgo == false,
                enabled && hasRegion,
            ) {
                onBetterFgoChanged(false)
            }
            ChoiceRow(
                "BetterFGO",
                "Cliente alternativo, com pasta própria",
                hasRegion && state.selectedProfile?.isBetterFgo == true,
                enabled && hasRegion,
            ) {
                onBetterFgoChanged(true)
            }
        }
    } else {
        Notice(
            "Somente o servidor Global",
            "Abra o Blue Archive e conclua o download inicial dos recursos antes de continuar.",
            info = true,
        )
    }
    Text(
        "A tradução será aplicada apenas ao servidor e ao aplicativo selecionados.",
        color = CafeMuted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) LatteGold.copy(alpha = .09f) else CafeSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) LatteGold else CafeLine),
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .selectable(selected, enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            Modifier.padding(16.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) LatteCream else CafeMuted,
                )
                Text(description, style = MaterialTheme.typography.bodySmall, color = CafeMuted)
            }
            RadioButton(selected, onClick = null, enabled = enabled)
        }
    }
}

internal fun regionName(profile: GameProfile): String =
    when (profile.bundleProfile) {
        GameProfile.JP -> "Japão"
        GameProfile.NA -> "América do Norte"
        else -> "Global"
    }

@Composable
private fun InstallStep(
    state: ImportUiState,
    game: TranslationGame,
    onGameClosedChanged: (Boolean) -> Unit,
    onChangeVersion: () -> Unit,
    onRemove: () -> Unit,
    onDownloadLatest: () -> Unit,
) {
    val installed =
        state.isInstalled && state.installationReceipt?.status == ReceiptStatus.INSTALLED
    val uninstallFinished =
        !state.isWorking &&
            state.events.any { it.messageCode == MessageCode.UNINSTALL_SUCCEEDED } &&
            state.installationReceipt == null &&
            state.installerFailureCode == null
    when {
        state.isWorking ->
            SectionHeading(
                if (state.events.lastOrNull()?.messageCode == MessageCode.UNINSTALL_PROGRESS)
                    "Desinstalando tradução"
                else operationTitle(state.currentPhase),
                if (state.currentPhase == OperationPhase.DOWNLOAD)
                    "O download continua em segundo plano. Acompanhe pela notificação."
                else "Mantenha o jogo fechado e o Fate Café aberto.",
            )
        installed && state.importedBundle == null ->
            SectionHeading("Tudo pronto para jogar", "A tradução está instalada em ${game.title}.")
        uninstallFinished ->
            SectionHeading(
                "Tradução desinstalada",
                "O jogo baixará os arquivos originais quando você abri-lo.",
            )
        state.importedBundle != null ->
            SectionHeading(
                "Instale a tradução",
                "O pacote foi baixado e autenticado. Só falta aplicar no jogo.",
            )
        else ->
            SectionHeading(
                "Sua tradução, pronta para baixar",
                "Vamos buscar o pacote mais recente para a versão escolhida.",
            )
    }
    Surface(color = CafeSurface, shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryRow("Servidor", state.selectedProfile?.let(::regionName) ?: "Não selecionado")
            SummaryRow(
                "Aplicativo",
                if (state.selectedProfile?.isBetterFgo == true) "BetterFGO" else game.title,
            )
            SummaryRow("Idioma", "Português do Brasil")
            state.selectedProfile?.let { profile ->
                state.catalog[profile]?.latest?.let {
                    SummaryRow("Versão disponível", it.tagName)
                    SummaryRow("Tamanho do download", transferBytes(it.size))
                }
            }
            state.importedBundle?.let { SummaryRow("Pacote", it.verifiedBundle.manifest.version) }
            if (installed) SummaryRow("Versão instalada", state.installationReceipt.bundleVersion)
            if (!state.isWorking && !installed) {
                TextButton(onChangeVersion, contentPadding = PaddingValues(0.dp)) {
                    Text("Alterar versão do jogo")
                }
            }
        }
    }
    if (state.isWorking) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            val progress = state.progress
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(progress.coerceIn(0f,1f)*100).toInt()}% nesta etapa",
                    color = LatteGold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                operationDescription(state.currentPhase),
                style = MaterialTheme.typography.bodyMedium,
                color = CafeMuted,
            )
            if (
                state.currentPhase == OperationPhase.DOWNLOAD &&
                    state.backgroundDownload.asset != null
            ) {
                DownloadDetails(state.backgroundDownload)
            }
        }
    }
    state.failureCode?.let {
        Notice("Não foi possível preparar o pacote", failureMessage(it), error = true)
    }
    val installerError =
        state.installerFailureCode
            ?: state.installerCapabilities?.takeUnless { it.available }?.reasonCode
    installerError?.let {
        Notice("A instalação precisa de atenção", installerFailureMessage(it), error = true)
    }
    if (
        !state.isWorking &&
            state.importedBundle != null &&
            state.installerCapabilities?.available == false &&
            state.installationReceipt?.status != ReceiptStatus.PENDING
    ) {
        TextButton(onDownloadLatest) { Text("Preparar pacote novamente") }
    }
    if (
        !state.isWorking &&
            state.events.lastOrNull()?.messageCode == MessageCode.OPERATION_CANCELLED
    ) {
        Notice("Operação cancelada", "Você pode tentar novamente quando quiser.", info = true)
    }

    if (!state.isWorking) {
        when {
            state.installationReceipt?.status == ReceiptStatus.PENDING ->
                Notice(
                    "Instalação interrompida",
                    "Remova os arquivos reconhecidos da instalação anterior antes de tentar novamente.",
                    error = true,
                )
            installed && state.importedBundle == null -> {
                Notice("Tradução instalada e verificada", "Você já pode abrir o jogo.")
                TextButton(onDownloadLatest) { Text("Buscar atualização da tradução") }
            }
            state.importedBundle != null -> {
                if (state.installerCapabilities?.available == true)
                    Notice("Pacote pronto", "Acesso ao jogo e espaço livre confirmados.")
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            state.gameClosedConfirmed,
                            role = Role.Checkbox,
                            onValueChange = onGameClosedChanged,
                        )
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(state.gameClosedConfirmed, onCheckedChange = null)
                    Text(
                        "Fechei completamente o ${game.title}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Confirme acima para liberar a instalação.",
                    color = CafeMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                Text(
                    "O download pode continuar com a tela apagada. A instalação será confirmada aqui, com o jogo fechado.",
                    color = CafeMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CafeGlyph(CafeIcon.DOWNLOAD)
                    Text(
                        "O pacote será baixado do GitHub e verificado antes da instalação.",
                        color = CafeMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (game == TranslationGame.BLUE_ARCHIVE)
                    Notice(
                        "Antes de instalar",
                        "Conclua o download dos recursos no Blue Archive. Depois, feche o jogo.",
                        info = true,
                    )
            }
        }
    }
    if (state.installationReceipt != null && !state.isWorking)
        ExpandableSection("Gerenciar tradução") {
            Text(
                "A desinstalação preserva arquivos que foram alterados pelo jogo. Os arquivos removidos serão baixados novamente pelo próprio jogo.",
                style = MaterialTheme.typography.bodyMedium,
                color = CafeMuted,
            )
            TextButton(onRemove) {
                Text("Desinstalar tradução", color = MaterialTheme.colorScheme.error)
            }
        }
    if (
        state.events.isNotEmpty() ||
            state.importedBundle != null ||
            installerError != null ||
            state.failureCode != null
    )
        TechnicalDetails(state)
}

@Composable
private fun SummaryRow(label: String, value: String) {
    // Separate rows keep long version tags readable with large system fonts.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = CafeMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ExpandableSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        HorizontalDivider(color = CafeLine)
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expandido" else "Recolhido" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = CafeMuted,
            )
            CafeGlyph(if (expanded) CafeIcon.CLOSE else CafeIcon.CHEVRON, color = CafeMuted)
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun TechnicalDetails(state: ImportUiState) {
    ExpandableSection("Detalhes técnicos") {
        DiagnosticCopyButton(state)
        state.selectedProfile?.let {
            Text(it.packageName, style = MaterialTheme.typography.bodySmall, color = CafeMuted)
        }
        state.events.forEach { event ->
            Column(
                Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(eventMessage(event.messageCode), style = MaterialTheme.typography.bodySmall)
                Text(
                    "${event.phase.name} · ${statusLabel(event.status)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CafeMuted,
                )
                event.fileRelativePath?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = CafeMuted)
                }
                event.bytesProcessed?.let {
                    Text(
                        formatBytes(it) +
                            (event.bytesTotal?.let { total -> " / ${formatBytes(total)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = CafeMuted,
                    )
                }
                event.failureCode?.let {
                    Text(
                        "Código: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadDetails(download: DownloadSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${transferBytes(download.bytes)} / ${transferBytes(download.asset?.size ?: 0)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LatteGold,
        )
        Text(
            when (download.status) {
                DownloadStatus.RETRYING -> "Conexão interrompida. Tentando novamente…"
                DownloadStatus.PAUSED -> "Download salvo para retomar."
                else ->
                    listOfNotNull(
                            download.bytesPerSecond?.let { "${transferBytes(it.toLong())}/s" },
                            download.remainingSeconds?.let { "Restante: ${transferEta(it)}" },
                        )
                        .joinToString(" · ")
                        .ifBlank { "Calculando velocidade…" }
            },
            style = MaterialTheme.typography.bodySmall,
            color = CafeMuted,
        )
    }
}

internal fun operationTitle(phase: OperationPhase?): String =
    when (phase) {
        OperationPhase.DOWNLOAD -> "Baixando a tradução"
        OperationPhase.AUTHENTICATE,
        OperationPhase.INSPECT -> "Verificando o pacote"
        OperationPhase.EXTRACT -> "Preparando os arquivos"
        OperationPhase.PRECHECK -> "Conferindo o jogo"
        OperationPhase.COMMIT -> "Aplicando a tradução"
        OperationPhase.VERIFY -> "Verificando os arquivos"
        OperationPhase.CLEANUP -> "Finalizando a operação"
        else -> "Buscando a tradução"
    }

private fun operationDescription(phase: OperationPhase?): String =
    when (phase) {
        OperationPhase.DOWNLOAD -> "O tempo de download depende da sua conexão."
        OperationPhase.AUTHENTICATE,
        OperationPhase.INSPECT,
        OperationPhase.EXTRACT -> "Conferindo a autenticidade e a integridade dos arquivos."
        OperationPhase.PRECHECK -> "Verificando o acesso ao jogo e o espaço disponível."
        OperationPhase.COMMIT,
        OperationPhase.VERIFY -> "Não abra o jogo enquanto os arquivos são aplicados e verificados."
        OperationPhase.CLEANUP -> "Removendo arquivos temporários. Aguarde a confirmação."
        else -> "Consultando o pacote mais recente no GitHub."
    }
