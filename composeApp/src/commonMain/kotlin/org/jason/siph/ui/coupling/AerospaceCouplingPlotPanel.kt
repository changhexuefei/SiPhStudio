package org.jason.siph.ui.coupling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.model.CouplingSampleUi
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill

internal val AerospacePlotBackground = Color(0xFF080D14)
internal val AerospacePlotPanel = Color(0xFF0B1119)
internal val AerospacePlotGridMajor = Color(0xFF26313E)
internal val AerospacePlotGridMinor = Color(0xFF18222E)

@Composable
internal fun AerospaceCouplingPlotPanel(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(CouplingPlotViewMode.Planar) }
    var rendererBackend by remember { mutableStateOf(AerospaceSurfaceBackend.SurfacePlot) }
    val summary = remember(samples) { buildPlotSummary(samples) }

    AerospacePanel(
        modifier = modifier,
        elevated = true,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "SIGNAL ANALYTICS",
            title = "COUPLING FIELD",
            caption = summary.caption,
            trailing = {
                TelemetryPill(
                    label = "VIEW",
                    value = viewMode.label,
                    tone = AerospacePalette.Accent,
                    active = true
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CouplingPlotViewMode.entries.forEach { mode ->
                AerospaceSegmentButton(
                    selected = viewMode == mode,
                    label = mode.label,
                    onClick = { viewMode = mode },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        PlotMetricsStrip(summary)

        when (viewMode) {
            CouplingPlotViewMode.Planar -> PlanarTelemetryView(
                samples = samples,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            CouplingPlotViewMode.Surface3d -> AerospacePowerSurface3d(
                samples = samples,
                selectedBackend = rendererBackend,
                onSelectBackend = { rendererBackend = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

private enum class CouplingPlotViewMode(val label: String) {
    Planar("2D ANALYTICS"),
    Surface3d("3D SURFACE")
}

private data class PlotSummary(
    val sampleCount: Int,
    val peakPowerDbm: Double?,
    val currentPowerDbm: Double?,
    val dynamicRangeDb: Double?
) {
    val caption: String
        get() = if (sampleCount == 0) {
            "Awaiting optical samples from the active alignment sequence"
        } else {
            "$sampleCount samples mapped across the current coupling field"
        }
}

@Composable
private fun PlotMetricsStrip(summary: PlotSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricTile(
            label = "SAMPLES",
            value = summary.sampleCount.toString(),
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = "CURRENT",
            value = formatPlotDbm(summary.currentPowerDbm),
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = "PEAK",
            value = formatPlotDbm(summary.peakPowerDbm),
            emphasized = summary.peakPowerDbm != null,
            accent = AerospacePalette.Success,
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = "DYNAMIC RANGE",
            value = summary.dynamicRangeDb?.let { "${roundPlotValue(it)} dB" } ?: "-- dB",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PlanarTelemetryView(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AerospacePlotFrame(
            eyebrow = "SEQUENCE TREND",
            title = "POWER VS SAMPLE",
            caption = "Optical power progression through the active search",
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.9f)
        ) {
            if (samples.isEmpty()) {
                AerospaceEmptyPlotState(
                    title = "NO TREND DATA",
                    caption = "Start a coupling sequence to populate the power timeline."
                )
            } else {
                AerospacePowerTrend(
                    samples = samples,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        AerospacePlotFrame(
            eyebrow = "SPATIAL FIELD",
            title = "XY POWER MAP",
            caption = "Scan path, current position, peak position and power contours",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.1f)
        ) {
            AerospaceHeatMap(
                samples = samples,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
internal fun AerospacePlotFrame(
    eyebrow: String,
    title: String,
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = AerospacePlotPanel,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = eyebrow,
                        style = MaterialTheme.typography.labelSmall,
                        color = AerospacePalette.Accent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = AerospacePalette.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = AerospacePalette.TextMuted
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun AerospaceEmptyPlotState(
    title: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = AerospacePalette.AccentContainer.copy(alpha = 0.62f),
                border = BorderStroke(1.dp, AerospacePalette.Accent.copy(alpha = 0.45f))
            ) {
                Text(
                    text = "∿",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = AerospacePalette.AccentBright,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = AerospacePalette.TextPrimary,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = AerospacePalette.TextMuted
            )
        }
    }
}

@Composable
internal fun AerospaceSegmentButton(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = when {
            !enabled -> AerospacePalette.Panel
            selected -> AerospacePalette.AccentContainer.copy(alpha = 0.72f)
            else -> AerospacePalette.PanelRaised
        },
        contentColor = when {
            !enabled -> AerospacePalette.TextMuted
            selected -> AerospacePalette.AccentBright
            else -> AerospacePalette.TextSecondary
        },
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> AerospacePalette.Border.copy(alpha = 0.45f)
                selected -> AerospacePalette.Accent.copy(alpha = 0.72f)
                else -> AerospacePalette.Border
            }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun buildPlotSummary(samples: List<CouplingSampleUi>): PlotSummary {
    val minPower = samples.minOfOrNull { it.powerDbm }
    val maxPower = samples.maxOfOrNull { it.powerDbm }

    return PlotSummary(
        sampleCount = samples.size,
        peakPowerDbm = maxPower,
        currentPowerDbm = samples.lastOrNull()?.powerDbm,
        dynamicRangeDb = if (minPower != null && maxPower != null) {
            maxPower - minPower
        } else {
            null
        }
    )
}

internal fun formatPlotDbm(value: Double?): String =
    value?.let { "${roundPlotValue(it)} dBm" } ?: "-- dBm"

internal fun roundPlotValue(value: Double): Double =
    kotlin.math.round(value * 100.0) / 100.0
