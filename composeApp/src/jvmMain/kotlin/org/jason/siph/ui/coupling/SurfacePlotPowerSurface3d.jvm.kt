package org.jason.siph.ui.coupling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    Surface(
        modifier = modifier,
        color = AerospacePlotPanel,
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
                columns = mesh.points.first().size,
                rows = mesh.points.size,
                values = mesh.points.flatten().map { it.power.toFloat() },
                xRange = mesh.xMin.toFloat()..mesh.xMax.toFloat(),
                yRange = mesh.yMin.toFloat()..mesh.yMax.toFloat()
            )
        }
        val state = remember(title, initialAzimuthDegrees, initialElevationDegrees) {
            SurfacePlotState(
                azimuthDegrees = initialAzimuthDegrees,
                elevationDegrees = initialElevationDegrees,
                zoom = 1f
            )
        }
        val config = remember(title, mesh.minPower, mesh.maxPower) {
            SurfacePlotConfig(
                title = title,
                xAxisLabel = "X (um)",
                yAxisLabel = "Y (um)",
                zAxisLabel = "Power (dBm)",
                zRange = mesh.minPower.toFloat()..mesh.maxPower.toFloat(),
                verticalScale = 1.1f,
                axisTickCount = 5,
                contourLevelCount = 8,
                style = SurfacePlotStyle(
                    backgroundColor = AerospacePlotBackground,
                    plotAreaColor = AerospacePlotBackground,
                    axisColor = AerospacePalette.TextSecondary,
                    gridColor = AerospacePlotGridMajor.copy(alpha = 0.75f),
                    meshColor = Color.White.copy(alpha = 0.28f),
                    textColor = AerospacePalette.TextSecondary,
                    tooltipBackgroundColor = AerospacePalette.PanelRaised,
                    tooltipTextColor = AerospacePalette.TextPrimary,
                    colorStops = listOf(
                        SurfaceColorStop(0f, Color(0xFF102A73)),
                        SurfaceColorStop(0.22f, Color(0xFF155BB5)),
                        SurfaceColorStop(0.45f, Color(0xFF00A6C7)),
                        SurfaceColorStop(0.65f, Color(0xFF38C982)),
                        SurfaceColorStop(0.82f, Color(0xFFE0D94A)),
                        SurfaceColorStop(1f, Color(0xFFFF6A3D))
                    )
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
