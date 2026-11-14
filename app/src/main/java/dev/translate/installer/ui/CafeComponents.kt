package dev.translate.installer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.translate.installer.R
import dev.translate.installer.ui.theme.*

internal enum class CafeIcon {
    BACK,
    NEXT,
    HELP,
    CHECK,
    DOWNLOAD,
    LOCK,
    CLOSE,
    CHEVRON,
    EXTERNAL,
}

/** Small, consistent line icons; decorative by default, named by their parent control. */
@Composable
internal fun CafeGlyph(icon: CafeIcon, modifier: Modifier = Modifier, color: Color = LatteGold) {
    Canvas(modifier.size(22.dp)) {
        val s = size.width / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(
                color,
                androidx.compose.ui.geometry.Offset(x * s, y * s),
                androidx.compose.ui.geometry.Offset(x2 * s, y2 * s),
                1.8f * s,
                StrokeCap.Round,
            )
        when (icon) {
            CafeIcon.BACK -> {
                line(19f, 12f, 5f, 12f)
                line(5f, 12f, 11f, 6f)
                line(5f, 12f, 11f, 18f)
            }
            CafeIcon.NEXT -> {
                line(5f, 12f, 19f, 12f)
                line(19f, 12f, 13f, 6f)
                line(19f, 12f, 13f, 18f)
            }
            CafeIcon.CHECK -> {
                line(5f, 12f, 10f, 17f)
                line(10f, 17f, 19f, 7f)
            }
            CafeIcon.DOWNLOAD -> {
                line(12f, 3f, 12f, 15f)
                line(7f, 10f, 12f, 15f)
                line(17f, 10f, 12f, 15f)
                line(4f, 17f, 4f, 21f)
                line(4f, 21f, 20f, 21f)
                line(20f, 21f, 20f, 17f)
            }
            CafeIcon.CLOSE -> {
                line(6f, 6f, 18f, 18f)
                line(6f, 18f, 18f, 6f)
            }
            CafeIcon.CHEVRON -> {
                line(8f, 10f, 12f, 14f)
                line(12f, 14f, 16f, 10f)
            }
            CafeIcon.EXTERNAL -> {
                line(8f, 16f, 19f, 5f)
                line(12f, 5f, 19f, 5f)
                line(19f, 5f, 19f, 12f)
                line(5f, 5f, 5f, 20f)
                line(5f, 20f, 20f, 20f)
            }
            CafeIcon.LOCK -> {
                drawRoundRect(
                    color,
                    androidx.compose.ui.geometry.Offset(5 * s, 10 * s),
                    androidx.compose.ui.geometry.Size(14 * s, 11 * s),
                    androidx.compose.ui.geometry.CornerRadius(2 * s),
                    style = Stroke(1.8f * s),
                )
                val path =
                    Path().apply {
                        moveTo(8 * s, 10 * s)
                        lineTo(8 * s, 7 * s)
                        cubicTo(8 * s, 1 * s, 16 * s, 1 * s, 16 * s, 7 * s)
                        lineTo(16 * s, 10 * s)
                    }
                drawPath(path, color, style = Stroke(1.8f * s))
                line(12f, 14f, 12f, 17f)
            }
            CafeIcon.HELP -> {
                drawCircle(color, 10 * s, style = Stroke(1.8f * s))
                val path =
                    Path().apply {
                        moveTo(9 * s, 8 * s)
                        cubicTo(9 * s, 4 * s, 17 * s, 5 * s, 15 * s, 10 * s)
                        lineTo(12 * s, 13 * s)
                    }
                drawPath(path, color, style = Stroke(1.8f * s))
                line(12f, 17f, 12f, 17.1f)
            }
        }
    }
}

@Composable
internal fun BrandHeader(trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.fate_cafe_logo),
            null,
            Modifier.size(46.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Fate Café", style = MaterialTheme.typography.titleMedium)
            Text(
                "Traduções para Android",
                color = CafeMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        trailing()
    }
}

@Composable
internal fun SectionHeading(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
        )
        if (description != null)
            Text(description, color = CafeMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Notice(
    title: String,
    message: String? = null,
    error: Boolean = false,
    info: Boolean = false,
) {
    val accent = if (error) MaterialTheme.colorScheme.error else if (info) LatteGold else CafeSage
    Surface(
        color = accent.copy(alpha = .08f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = .3f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CafeGlyph(if (error || info) CafeIcon.HELP else CafeIcon.CHECK, color = accent)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = accent, style = MaterialTheme.typography.titleSmall)
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick,
        Modifier.fillMaxWidth().heightIn(min = 54.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun StepProgress(current: Int) {
    val labels = listOf("Acesso", "Versão", "Instalar")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == current
            val color = if (index <= current) LatteGold else CafeMuted
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .height(3.dp)
                        .background(if (index <= current) LatteGold else CafeLine, CircleShape)
                )
                Text(
                    "${index+1}. $label",
                    color = color,
                    style =
                        if (active) MaterialTheme.typography.labelLarge
                        else MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
