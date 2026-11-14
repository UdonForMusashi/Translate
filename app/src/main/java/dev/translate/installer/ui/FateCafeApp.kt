package dev.translate.installer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.translate.installer.R
import dev.translate.installer.domain.GameProfile
import dev.translate.installer.ui.theme.*

internal enum class TranslationGame(
    val title: String,
    val regions: String,
    val artwork: Int,
    val profiles: List<GameProfile>,
) {
    FGO(
        "Fate/Grand Order",
        "Japão e América do Norte",
        R.drawable.fgo_key_art,
        listOf(GameProfile.JP, GameProfile.NA),
    ),
    BLUE_ARCHIVE(
        "Blue Archive",
        "Servidor Global",
        R.drawable.blue_archive_key_art,
        listOf(GameProfile.BA),
    ),
}

@Composable
fun FateCafeApp(
    state: ImportUiState,
    onVerifyAccess: () -> Unit,
    onProfileSelected: (GameProfile) -> Unit,
    onBetterFgoChanged: (Boolean) -> Unit,
    onClearProfileSelection: () -> Unit,
    onResetCompletedInstallation: () -> Unit,
    onDownloadLatest: () -> Unit,
    onCancelImport: () -> Unit,
    onGameClosedChanged: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf<TranslationGame?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val game = destination
    if (game == null) {
        GameCatalogScreen(
            onGameSelected = {
                onClearProfileSelection()
                destination = it
            },
            onHelp = { showHelp = true },
        )
    } else {
        key(game) {
            InstallerScreen(
                state,
                game,
                onBackToCatalog = {
                    if (!state.isWorking) {
                        onClearProfileSelection()
                        destination = null
                    }
                },
                onVerifyAccess,
                onProfileSelected,
                onBetterFgoChanged,
                onResetCompletedInstallation,
                onDownloadLatest,
                onCancelImport,
                onGameClosedChanged,
                onInstall,
                onUninstall,
            )
        }
    }
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
}

@Composable
private fun GameCatalogScreen(onGameSelected: (TranslationGame) -> Unit, onHelp: () -> Unit) {
    val uri = LocalUriHandler.current
    Scaffold(containerColor = Espresso) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                BrandHeader {
                    IconButton(onHelp, Modifier.semantics { contentDescription = "Ajuda" }) {
                        CafeGlyph(CafeIcon.HELP, color = CafeMuted)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Suas histórias.\nEm português.",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        "Escolha um jogo para instalar a tradução.",
                        color = CafeMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TranslationGame.entries.forEach { game ->
                        CatalogGameCard(game) { onGameSelected(game) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onHelp)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CafeGlyph(CafeIcon.HELP, color = CafeMuted)
                    Column(Modifier.weight(1f)) {
                        Text("Primeira vez por aqui?", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Veja como preparar seu aparelho.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CafeMuted,
                        )
                    }
                    CafeGlyph(CafeIcon.NEXT, color = CafeMuted)
                }
                HorizontalDivider(color = CafeLine)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Feito pela comunidade",
                        style = MaterialTheme.typography.bodySmall,
                        color = CafeMuted,
                    )
                    TextButton({ uri.openUri("https://discord.gg/E2UJh7zwzy") }) {
                        Text("União Fate Café", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(6.dp))
                        CafeGlyph(CafeIcon.EXTERNAL, Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogGameCard(game: TranslationGame, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = CafeSurface,
        modifier =
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = "${game.title}, ${game.regions}. Selecionar jogo"
            },
    ) {
        BoxWithConstraints {
            val artworkWidth = if (maxWidth < 320.dp) 80.dp else 112.dp
            // The text sets the height. Artwork's intrinsic aspect ratio must not stretch the card.
            Box(Modifier.matchParentSize()) {
                Image(
                    painterResource(game.artwork),
                    null,
                    Modifier.width(artworkWidth).fillMaxHeight(),
                    contentScale = ContentScale.Crop,
                    alignment =
                        if (game == TranslationGame.FGO) Alignment.TopCenter else Alignment.Center,
                )
            }
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .padding(
                        start = artworkWidth + 20.dp,
                        end = 20.dp,
                        top = 20.dp,
                        bottom = 20.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Tradução PT-BR",
                    style = MaterialTheme.typography.labelSmall,
                    color = LatteGold,
                )
                Text(game.title, style = MaterialTheme.typography.titleLarge)
                Text(game.regions, style = MaterialTheme.typography.bodySmall, color = CafeMuted)
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Selecionar jogo",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = LatteGold,
                    )
                    CafeGlyph(CafeIcon.NEXT, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
internal fun GameBanner(game: TranslationGame) {
    Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(12.dp))) {
        Image(
            painterResource(game.artwork),
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            // Keep Arona's face in the short banner; the catalog uses the centered full scene.
            alignment =
                if (game == TranslationGame.BLUE_ARCHIVE) BiasAlignment(0f, -.33f)
                else Alignment.TopCenter,
        )
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Espresso.copy(alpha = .97f),
                            Espresso.copy(alpha = .8f),
                            Espresso.copy(alpha = .15f),
                        )
                    )
                )
        )
        Column(
            Modifier.align(Alignment.CenterStart).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(game.title, style = MaterialTheme.typography.titleLarge)
            Text(
                "Tradução em português",
                style = MaterialTheme.typography.bodySmall,
                color = LatteGoldBright,
            )
        }
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Antes de começar") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Instale o jogo e conclua o download inicial dos recursos. Depois, feche o jogo antes de aplicar a tradução."
                )
                Text(
                    "Android 10 ou mais recente: instale e inicie o Shizuku para autorizar o acesso aos arquivos."
                )
                Text(
                    "Android 7, 8 e 9: basta autorizar o armazenamento. O Shizuku não é necessário."
                )
                Text(
                    "O uso de traduções pode resultar em penalidades na conta do jogo. Use por sua conta e risco.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CafeMuted,
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Entendi") } },
        dismissButton = {
            TextButton({ uri.openUri("https://shizuku.rikka.app/guide/setup/") }) {
                Text("Guia do Shizuku")
            }
        },
    )
}
