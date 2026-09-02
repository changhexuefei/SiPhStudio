package org.jason.siph.ui.coupling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.TelemetryPill

@Composable
fun CouplingLogPanel(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    AerospacePanel(
        modifier = modifier,
        elevated = false
    ) {
        AerospaceSectionHeader(
            eyebrow = "SEQUENCE EVENTS",
            title = "MISSION LOG",
            caption = "Latest coupling operations and safety messages",
            trailing = {
                TelemetryPill(
                    label = "EVENTS",
                    value = logs.size.toString(),
                    tone = AerospacePalette.Accent,
                    active = logs.isNotEmpty()
                )
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (logs.isEmpty()) {
                item {
                    Text(
                        text = "000  //  NO EVENTS RECORDED",
                        style = MaterialTheme.typography.bodySmall,
                        color = AerospacePalette.TextMuted,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                val visibleLogs = logs.takeLast(300)
                itemsIndexed(visibleLogs) { index, line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = (index + 1).toString().padStart(3, '0'),
                            style = MaterialTheme.typography.labelSmall,
                            color = AerospacePalette.Accent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "//",
                            style = MaterialTheme.typography.labelSmall,
                            color = AerospacePalette.BorderStrong,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
