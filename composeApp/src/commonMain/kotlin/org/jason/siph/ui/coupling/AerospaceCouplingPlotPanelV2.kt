package org.jason.siph.ui.coupling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.model.CouplingSampleUi
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill

/**
 * 当前信号分析面板的稳定入口。
 *
 * 3D 页面只调用 [AerospacePowerSurface3d]，不再维护第二套会逐帧创建
 * List/SurfaceCell/Path 的 Canvas 投影实现。
 */
@Composable
internal fun AerospaceCouplingPlotPanelV2(
    samples: List<CouplingSampleUi>,
    modifier: Modifier = Modifier
) {
    var viewMode by remember { mutableStateOf(CouplingPlotViewModeV2.Planar) }
    var rendererBackend by remember { mutableStateOf(AerospaceSurfaceBackend.SurfacePlot) }
    val summary = remember(samples) { buildPlotSummaryV2(samples) }

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
            CouplingPlotViewModeV2.entries.forEach { mode ->
                AerospaceSegmentButton(
                    selected = viewMode == mode,
                    label = mode.label,
                    onClick = { viewMode = mode },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        PlotMetricsStripV2(summary)

        when (viewMode) {
            CouplingPlotViewModeV2.Planar -> PlanarTelemetryViewV2(
                samples = samples,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            CouplingPlotViewModeV2.Surface3d -> AerospacePowerSurface3d(
                samples = samples,
                selectedBackend = rendererBackend,
                onSelectBackend = { rendererBackend = it },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

private enum class CouplingPlotViewModeV2(val label: String) {
    Planar("2D ANALYTICS"),
    Surface3d("3D SURFACE")
}

private data class PlotSummaryV2(
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
private fun PlotMetricsStripV2(summary: PlotSummaryV2) {
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
            modifier = Modifier.weight(1f),
            accent = AerospacePalette.Success,
            emphasized = summary.peakPowerDbm != null
        )
        MetricTile(
            label = "DYNAMIC RANGE",
            value = summary.dynamicRangeDb?.let { "${roundPlotValue(it)} dB" } ?: "-- dB",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PlanarTelemetryViewV2(
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
            modifier = Modifier.fillMaxWidth().weight(0.9f)
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
            modifier = Modifier.fillMaxWidth().weight(1.1f)
        ) {
            AerospaceHeatMap(
                samples = samples,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun buildPlotSummaryV2(samples: List<CouplingSampleUi>): PlotSummaryV2 {
    val finitePower = samples.map { it.powerDbm }.filter(Double::isFinite)
    val minPower = finitePower.minOrNull()
    val maxPower = finitePower.maxOrNull()

    return PlotSummaryV2(
        sampleCount = samples.size,
        peakPowerDbm = maxPower,
        currentPowerDbm = samples.lastOrNull()?.powerDbm?.takeIf(Double::isFinite),
        dynamicRangeDb = if (minPower != null && maxPower != null) {
            maxPower - minPower
        } else {
            null
        }
    )
}
