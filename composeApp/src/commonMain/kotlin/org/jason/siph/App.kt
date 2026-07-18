package org.jason.siph

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.siphtools.CouplingToolScreen
import org.jason.siph.ui.state.createDemoCouplingToolStore

@Composable
@androidx.compose.ui.tooling.preview.Preview
fun App() {
    SiPhTheme {
        val scope = rememberCoroutineScope()
        val store = remember(scope) {
            createDemoCouplingToolStore(scope)
        }
        val state by store.state.collectAsState()

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            CouplingToolScreen(
                state = state,
                onAction = store::dispatch
            )
        }
    }
}

@Composable
private fun SiPhTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0F766E),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFCFF7EF),
            onPrimaryContainer = Color(0xFF063F39),
            secondary = Color(0xFF42526E),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE2E8F0),
            onSecondaryContainer = Color(0xFF1F2937),
            tertiary = Color(0xFFB45309),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFE8C2),
            onTertiaryContainer = Color(0xFF5F3100),
            background = Color(0xFFF5F7FA),
            onBackground = Color(0xFF18202F),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF18202F),
            surfaceVariant = Color(0xFFE7ECF2),
            onSurfaceVariant = Color(0xFF526070),
            outline = Color(0xFFC7D0DC),
            error = Color(0xFFB42318),
            errorContainer = Color(0xFFFEE4E2),
            onErrorContainer = Color(0xFF7A271A)
        ),
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        ),
        content = content
    )
}
