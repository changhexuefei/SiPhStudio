package org.jason.siph.ui.coupling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jason.measure.uikit.surfaceplot.SurfaceColorStop
import com.jason.measure.uikit.surfaceplot.SurfaceGrid
import com.jason.measure.uikit.surfaceplot.SurfacePlot
import com.jason.measure.uikit.surfaceplot.SurfacePlotConfig
import com.jason.measure.uikit.surfaceplot.SurfacePlotState
import com.jason.measure.uikit.surfaceplot.SurfacePlotStyle
import org.jason.siph.ui.theme.AerospacePalette

@Composable
internal actual fun SurfacePlotPowerSurface3d(
    mesh: SurfaceMesh?,
    title: String,
    initialAzimuthDegrees: Float,
    initialElevationDegrees: Float,
    modifier: Modifier
) {
    val spec = CouplingSurfaceRenderSpec

    Surface(
        modifier = modifier,
        color = spec.plotAreaColor,
        border = BorderStroke(1.dp, AerospacePalette.Border)
    ) {
        if (mesh == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AerospaceEmptyPlotState(
                    title = "NO SURFACE DATA",
                    caption = "At least three samples spanning X, Y and power are required."
                )
            }
            return@Surface
        }

        val data = remember(mesh) {
            SurfaceGrid(
                columns = mesh.columnCount,
                rows = mesh.rowCount,
                values = mesh.powerValues(),
                xRange = mesh.xRangeFloat,
                yRange = mesh.yRangeFloat
            )
        }
        val state = remember(title, initialAzimuthDegrees, initialElevationDegrees) {
            SurfacePlotState(
                azimuthDegrees = initialAzimuthDegrees,
                elevationDegrees = initialElevationDegrees,
                zoom = 1f
            )
        }
        val config = remember(title, mesh.minPower, mesh.maxPower, spec) {
            SurfacePlotConfig(
                title = title,
                xAxisLabel = spec.xAxisLabel,
                yAxisLabel = spec.yAxisLabel,
                zAxisLabel = spec.zAxisLabel,
                zRange = mesh.powerRangeFloat,
                verticalScale = spec.verticalScale,
                axisTickCount = spec.axisTickCount,
                contourLevelCount = spec.contourLevelCount,
                style = SurfacePlotStyle(
                    backgroundColor = spec.backgroundColor,
                    plotAreaColor = spec.plotAreaColor,
                    axisColor = spec.axisColor,
                    gridColor = spec.gridColor,
                    meshColor = spec.wireframeColor,
                    textColor = spec.textColor,
                    tooltipBackgroundColor = AerospacePalette.PanelRaised,
                    tooltipTextColor = AerospacePalette.TextPrimary,
                    colorStops = AerospaceSurfaceColorScale.stops.map { stop ->
                        SurfaceColorStop(stop.position, stop.color)
                    }
                )
            )
        }

        SurfacePlot(
            data = data,
            modifier = Modifier.fillMaxSize(),
            config = config,
            state = state
        )
    }
}
