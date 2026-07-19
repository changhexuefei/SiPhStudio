package org.jason.siph.ui.coupling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Surface Plot is available in the desktop JVM build.",
                color = AerospacePalette.TextSecondary
            )
        }
    }
}
