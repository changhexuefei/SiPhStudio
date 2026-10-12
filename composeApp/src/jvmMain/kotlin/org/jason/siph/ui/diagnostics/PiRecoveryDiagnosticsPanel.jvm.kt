package org.jason.siph.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jason.pi.gcs.simulation.PhysicalReadOnlyEvidenceRecovery
import org.jason.pi.gcs.simulation.PhysicalReadOnlyPublicationRecoveryReport
import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryAuditRevalidation
import org.jason.pi.gcs.simulation.PhysicalReadOnlyRecoveryDiagnosticsExport

/**
 * JVM-only local filesystem inspection. The UI never owns or connects any PI device.
 * No operator resolution is supplied, so a matching audit status is never invented.
 */
@Composable
fun PiRecoveryDiagnosticsPanel(modifier: Modifier = Modifier) {
    var parent by remember { mutableStateOf("") }
    var publishedName by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<PhysicalReadOnlyRecoveryDiagnosticsExport.Dto?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline Recovery Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text("Read-only inspection. This screen never clears locks, approves recovery, or controls a PI device.")
        OutlinedTextField(parent, { parent = it; result = null; failure = null },
            label = { Text("Evidence parent directory") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(publishedName, { publishedName = it; result = null; failure = null },
            label = { Text("Published directory name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            result = null
            failure = null
            val (validated, inputError) = PiRecoveryDiagnosticsInput.validate(parent, publishedName)
            if (inputError != null || validated == null) {
                failure = "INSPECTION_BLOCKED: " + (inputError?.name ?: "INVALID_INPUT")
            } else try {
                // A missing approval record is deliberately not accepted as verified evidence.
                val report = PhysicalReadOnlyPublicationRecoveryReport.collect(
                    validated.parent, validated.publishedName, null
                )
                val blocked = report.decision == PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK
                val inspection = PhysicalReadOnlyRecoveryAuditRevalidation.Result(
                    status = if (blocked) {
                        PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED
                    } else {
                        PhysicalReadOnlyRecoveryAuditRevalidation.Status.CHANGED_OR_INVALID
                    },
                    currentDecision = report.decision,
                    currentReasons = report.reasons
                )
                val dto = PhysicalReadOnlyRecoveryDiagnosticsExport.export(inspection)
                check(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto).isEmpty()) {
                    "Invalid diagnostics contract"
                }
                result = dto
            } catch (_: Exception) {
                // Do not put filesystem paths or raw exception text into UI diagnostics.
                failure = "INSPECTION_BLOCKED: Offline inspection failed."
            }
        }, enabled = parent.isNotBlank() && publishedName.isNotBlank()) { Text("Inspect offline") }

        if (failure != null) Text(failure!!, color = MaterialTheme.colorScheme.error)
        result?.let { dto ->
            Text("Schema: ${dto.schemaVersion}")
            Text("Audit status: ${dto.status}")
            Text("Recovery decision: ${dto.decision}")
            Text("Reason codes: ${dto.reasonCodes.ifEmpty { listOf("NONE") }.joinToString(", ")}")
            Text("Failure category: ${dto.failureCategory}")
            Text("Failed sample: ${dto.failedSample?.toString() ?: "N/A"}")
            Text("No approval or automatic resume is possible from this screen.")
        }
    }
}
