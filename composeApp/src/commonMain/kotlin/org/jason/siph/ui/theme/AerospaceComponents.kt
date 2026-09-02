package org.jason.siph.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AerospaceBackdrop(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        AerospacePalette.Void,
                        AerospacePalette.Background,
                        Color(0xFF0A0F16)
                    )
                )
            )
            .drawBehind {
                val minor = 48.dp.toPx()
                val major = minor * 4f
                var x = 0f
                while (x <= size.width) {
                    val majorLine = ((x / minor).toInt() % 4) == 0
                    drawLine(
                        color = if (majorLine) {
                            AerospacePalette.Grid.copy(alpha = 0.85f)
                        } else {
                            AerospacePalette.Grid.copy(alpha = 0.38f)
                        },
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = if (majorLine) 1.dp.toPx() else 0.5.dp.toPx()
                    )
                    x += minor
                }

                var y = 0f
                while (y <= size.height) {
                    val majorLine = ((y / minor).toInt() % 4) == 0
                    drawLine(
                        color = if (majorLine) {
                            AerospacePalette.Grid.copy(alpha = 0.85f)
                        } else {
                            AerospacePalette.Grid.copy(alpha = 0.38f)
                        },
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = if (majorLine) 1.dp.toPx() else 0.5.dp.toPx()
                    )
                    y += minor
                }

                drawLine(
                    color = AerospacePalette.Accent.copy(alpha = 0.11f),
                    start = Offset(size.width * 0.62f, 0f),
                    end = Offset(size.width * 0.43f, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            },
        contentAlignment = contentAlignment,
        content = content
    )
}

@Composable
fun AerospacePanel(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    highlighted: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val borderColor = if (highlighted) {
        AerospacePalette.Accent.copy(alpha = 0.58f)
    } else {
        AerospacePalette.Border.copy(alpha = 0.92f)
    }
    val containerColor = when {
        highlighted -> AerospacePalette.AccentContainer.copy(alpha = 0.54f)
        elevated -> AerospacePalette.PanelRaised
        else -> AerospacePalette.Panel.copy(alpha = 0.96f)
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = if (elevated) 8.dp else 0.dp,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
fun AerospaceSectionHeader(
    eyebrow: String,
    title: String,
    caption: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.Accent,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing
        )
    }
}

@Composable
fun TelemetryPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: Color = AerospacePalette.Accent,
    active: Boolean = true
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = AerospacePalette.PanelRaised,
        border = BorderStroke(
            1.dp,
            if (active) tone.copy(alpha = 0.42f) else AerospacePalette.Border
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (active) tone else AerospacePalette.TextMuted,
                        shape = CircleShape
                    )
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = AerospacePalette.Accent,
    emphasized: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (emphasized) {
            AerospacePalette.AccentContainer.copy(alpha = 0.55f)
        } else {
            AerospacePalette.PanelRaised
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (emphasized) accent.copy(alpha = 0.55f) else AerospacePalette.Border
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (emphasized) accent else MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    color: Color = AerospacePalette.Border,
    thickness: Dp = 1.dp
) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .background(color)
            .padding(top = thickness)
    )
}
