package org.jason.siph.ui.positioner


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jason.siph.domain.positioner.OpticalPose
import kotlin.math.abs

@Composable
fun PoseDisplayCard(
    title: String,
    pose: OpticalPose,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PoseMetric("X", formatUm(pose.xUm), Modifier.weight(1f))
                PoseMetric("Y", formatUm(pose.yUm), Modifier.weight(1f))
                PoseMetric("Z", formatUm(pose.zUm), Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PoseMetric("U", formatDeg(pose.uDeg), Modifier.weight(1f))
                PoseMetric("V", formatDeg(pose.vDeg), Modifier.weight(1f))
                PoseMetric("W", formatDeg(pose.wDeg), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PoseMetric(
    name: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatUm(value: Double): String {
    return "${formatNumber(value)} μm"
}

private fun formatDeg(value: Double): String {
    return "${formatNumber(value)}°"
}

private fun formatNumber(value: Double): String {
    val normalized = if (abs(value) < 1e-9) 0.0 else value
    return (kotlin.math.round(normalized * 1000.0) / 1000.0).toString()
}