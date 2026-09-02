package org.jason.siph.ui.production

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.production.DistributedCoordinatorBackend
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospacePanel
import org.jason.siph.ui.theme.AerospaceSectionHeader
import org.jason.siph.ui.theme.MetricTile
import org.jason.siph.ui.theme.TelemetryPill

@Composable
fun ProductionClusterPanel(
    state: ProductionClusterUiState,
    onAction: (ProductionClusterAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val configured = state.coordinator.configured
    val degraded = configured && !state.coordinator.healthy
    val backendText = when (state.coordinator.backend) {
        DistributedCoordinatorBackend.InMemory -> "DIGITAL"
        DistributedCoordinatorBackend.PostgreSql -> "POSTGRESQL"
        DistributedCoordinatorBackend.Unavailable -> "NOT CONFIGURED"
    }

    AerospacePanel(
        modifier = modifier,
        elevated = true,
        highlighted = state.simulationBackend && configured && !degraded,
        contentPadding = PaddingValues(14.dp)
    ) {
        AerospaceSectionHeader(
            eyebrow = "PRODUCTION INFRASTRUCTURE",
            title = "MULTI-WORKSTATION COORDINATION",
            caption = state.errorMessage ?: state.message,
            trailing = {
                TelemetryPill(
                    label = "COORDINATOR",
                    value = when {
                        degraded -> "DEGRADED"
                        configured && state.coordinator.healthy -> "READY"
                        else -> "HOLD"
                    },
                    tone = when {
                        degraded -> AerospacePalette.Warning
                        configured && state.coordinator.healthy -> AerospacePalette.Success
                        else -> AerospacePalette.TextMuted
                    },
                    active = configured && state.coordinator.healthy
                )
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                label = "BACKEND",
                value = backendText,
                emphasized = configured,
                accent = if (configured) AerospacePalette.Accent else AerospacePalette.TextMuted,
                modifier = Modifier.weight(1.2f)
            )
            MetricTile(
                label = "WORKERS",
                value = state.workers.size.toString(),
                emphasized = state.workers.isNotEmpty(),
                modifier = Modifier.weight(0.75f)
            )
            MetricTile(
                label = "ONLINE",
                value = state.onlineWorkers.toString(),
                emphasized = state.onlineWorkers > 0,
                accent = AerospacePalette.Success,
                modifier = Modifier.weight(0.75f)
            )
            MetricTile(
                label = "BUSY",
                value = state.busyWorkers.toString(),
                emphasized = state.busyWorkers > 0,
                accent = AerospacePalette.Accent,
                modifier = Modifier.weight(0.65f)
            )
            MetricTile(
                label = "MES QUEUE",
                value = state.pendingMesEvents.toString(),
                emphasized = state.pendingMesEvents > 0,
                accent = AerospacePalette.Warning,
                modifier = Modifier.weight(0.8f)
            )
            MetricTile(
                label = "AUDIT QUEUE",
                value = state.pendingAuditEvents.toString(),
                emphasized = state.pendingAuditEvents > 0,
                accent = AerospacePalette.Warning,
                modifier = Modifier.weight(0.9f)
            )
            MetricTile(
                label = "DELIVERED",
                value = state.deliveredEvents.toString(),
                emphasized = state.deliveredEvents > 0,
                accent = AerospacePalette.Success,
                modifier = Modifier.weight(0.8f)
            )
            MetricTile(
                label = "DEAD LETTER",
                value = state.deadLetterEvents.toString(),
                emphasized = state.deadLetterEvents > 0,
                accent = AerospacePalette.Danger,
                modifier = Modifier.weight(0.95f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { onAction(ProductionClusterAction.DispatchOutbox) },
                enabled = state.canOperateDemo,
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AerospacePalette.TextPrimary,
                    contentColor = AerospacePalette.Void,
                    disabledContainerColor = AerospacePalette.PanelHover,
                    disabledContentColor = AerospacePalette.TextMuted
                )
            ) {
                Text("DISPATCH OUTBOX", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { onAction(ProductionClusterAction.Heartbeat) },
                enabled = state.canOperateDemo,
                modifier = Modifier.weight(0.75f).heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text("HEARTBEAT")
            }

            OutlinedButton(
                onClick = { onAction(ProductionClusterAction.ReapExpired) },
                enabled = state.canOperateDemo,
                modifier = Modifier.weight(0.65f).heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, AerospacePalette.Warning.copy(alpha = 0.72f))
            ) {
                Text("REAP", color = AerospacePalette.Warning)
            }

            OutlinedButton(
                onClick = { onAction(ProductionClusterAction.Refresh) },
                enabled = !state.busy,
                modifier = Modifier.weight(0.65f).heightIn(min = 40.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text("REFRESH")
            }

            Text(
                text = if (state.simulationBackend) {
                    "DIGITAL CLUSTER // Worker capability matching, leases, fencing tokens, heartbeat expiry, MES Outbox and remote audit replication are exercised without measurement hardware."
                } else {
                    "READ ONLY // Real cluster operations require PostgreSQL, verified worker capabilities, enterprise identity, configured MES and remote append-only audit services."
                },
                style = MaterialTheme.typography.labelSmall,
                color = AerospacePalette.TextMuted,
                modifier = Modifier.weight(2.3f)
            )
        }
    }
}
