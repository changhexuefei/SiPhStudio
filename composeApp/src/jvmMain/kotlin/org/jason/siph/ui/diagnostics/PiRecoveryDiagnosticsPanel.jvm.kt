package org.jason.siph.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var request by remember { mutableStateOf<InspectionRequest?>(null) }
    var serial by remember { mutableStateOf(0L) }
    var inspecting by remember { mutableStateOf(false) }
    val epochs = remember { PiRecoveryInspectionEpoch() }
    DisposableEffect(epochs) {
        onDispose { epochs.invalidate() }
    }

    LaunchedEffect(request) {
        val active = request ?: run { inspecting = false; return@LaunchedEffect }
        inspecting = true
        result = null
        failure = null
        try {
            val outcome = withContext(Dispatchers.IO) {
                val (validated, inputError) = PiRecoveryDiagnosticsInput.validate(
                    active.parent, active.name
                )
                if (validated == null || inputError != null) {
                    InspectionOutcome(null, "INSPECTION_BLOCKED: " + (inputError?.name ?: "INVALID_INPUT"))
                } else {
                    val report = PhysicalReadOnlyPublicationRecoveryReport.collect(
                        validated.parent, validated.publishedName, null
                    )
                    val blocked = report.decision == PhysicalReadOnlyEvidenceRecovery.Decision.BLOCK
                    val inspection = PhysicalReadOnlyRecoveryAuditRevalidation.Result(
                        status = if (blocked) {
                            PhysicalReadOnlyRecoveryAuditRevalidation.Status.INSPECTION_BLOCKED
                        } else PhysicalReadOnlyRecoveryAuditRevalidation.Status.CHANGED_OR_INVALID,
                        currentDecision = report.decision,
                        currentReasons = report.reasons
                    )
                    val dto = PhysicalReadOnlyRecoveryDiagnosticsExport.export(inspection)
                    check(PhysicalReadOnlyRecoveryDiagnosticsExport.validate(dto).isEmpty()) {
                        "Invalid diagnostics contract"
                    }
                    InspectionOutcome(dto, null)
                }
            }
            if (epochs.isCurrent(active.id)) {
                result = outcome.dto
                failure = outcome.error
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (epochs.isCurrent(active.id)) {
                failure = "INSPECTION_BLOCKED: Offline inspection failed."
            }
        } finally {
            if (epochs.complete(active.id)) inspecting = false
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline Recovery Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text("Read-only inspection. This screen never clears locks, approves recovery, or controls a PI device.")
        OutlinedTextField(parent, { parent = it; epochs.invalidate(); request = null; result = null; failure = null; inspecting = false },
            label = { Text("Evidence parent directory") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(publishedName, { publishedName = it; epochs.invalidate(); request = null; result = null; failure = null; inspecting = false },
            label = { Text("Published directory name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                serial = epochs.begin()
                request = InspectionRequest(serial, parent, publishedName)
                result = null
                failure = null
            },
            enabled = parent.isNotBlank() && publishedName.isNotBlank() && !inspecting
        ) { Text(if (inspecting) "Inspecting..." else "Inspect offline") }

        failure?.let { message ->
            val code = message.substringAfter("INSPECTION_BLOCKED: ", "INSPECTION_FAILED")
            val view = PiRecoveryDiagnosticsPresentation.inputFailure(code)
            Text(view.headline, color = MaterialTheme.colorScheme.error)
            Text(view.guidance)
            Text(view.detail)
        }
        result?.let { dto ->
            val view = PiRecoveryDiagnosticsPresentation.from(dto)
            val tone = when (view.severity) {
                PiRecoveryDiagnosticsPresentation.Severity.BLOCKED -> MaterialTheme.colorScheme.errorContainer
                PiRecoveryDiagnosticsPresentation.Severity.OPERATOR_ACTION -> MaterialTheme.colorScheme.tertiaryContainer
                PiRecoveryDiagnosticsPresentation.Severity.REVIEW_REQUIRED -> MaterialTheme.colorScheme.secondaryContainer
                PiRecoveryDiagnosticsPresentation.Severity.INFORMATIONAL -> MaterialTheme.colorScheme.surfaceVariant
            }
            Surface(color = tone, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(view.headline, style = MaterialTheme.typography.titleMedium)
                    Text(view.guidance)
                }
            }
            Text(view.detail)
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

private data class InspectionRequest(val id: Long, val parent: String, val name: String)
private data class InspectionOutcome(
    val dto: PhysicalReadOnlyRecoveryDiagnosticsExport.Dto?,
    val error: String?
)
