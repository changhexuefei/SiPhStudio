package org.jason.pi.gcs.simulation

/**
 * Pure, offline approval-record validator. It does NOT connect to a controller,
 * dispatch a GCS command, or certify operational/physical safety.
 */
data class PhysicalReadOnlyApprovalRecord(
    val equipmentOwner: String,
    val authorizationReference: String,
    val controllerModel: String,
    val controllerSerialReference: String,
    val firmwareVersion: String,
    val gcsManualRevision: String,
    val stageModel: String,
    val axisMappingConfirmed: Boolean,
    val endpointVerified: Boolean,
    val safetyProcedureReference: String,
    val interlocksVerified: Boolean,
    val emergencyStopVerified: Boolean,
    val operatorReference: String,
    val witnessReference: String,
    val approvalDate: String,
    val explicitlyApprovedQueries: Set<String>
)

data class PhysicalReadOnlyPreflightVerdict(
    val approvedForSeparateManualReview: Boolean,
    val reasons: List<String>,
    val source: String = "PHYSICAL_READONLY_PREPARATION",
    val hardwareTested: Boolean = false,
    val motionAuthorization: Boolean = false
)

/** A complete record allows review only, never execution or motion. */
object PhysicalReadOnlyPreflightGate {
    val candidateQueries: Set<String> =
        setOf("*IDN?", "ERR?", "POS? X Y Z U V W")

    fun evaluate(record: PhysicalReadOnlyApprovalRecord?): PhysicalReadOnlyPreflightVerdict {
        if (record == null) return PhysicalReadOnlyPreflightVerdict(
            approvedForSeparateManualReview = false,
            reasons = listOf("MISSING_APPROVAL_RECORD")
        )
        val failures = mutableListOf<String>()
        fun required(value: String, field: String) {
            if (value.isBlank() || value.trim().uppercase() in
                setOf("NOT PROVIDED", "NOT VERIFIED", "NOT APPROVED", "NOT PERFORMED"))
                failures += "MISSING_$field"
        }
        required(record.equipmentOwner, "OWNER")
        required(record.authorizationReference, "AUTHORIZATION")
        required(record.controllerModel, "CONTROLLER_MODEL")
        required(record.controllerSerialReference, "SERIAL_REFERENCE")
        required(record.firmwareVersion, "FIRMWARE")
        required(record.gcsManualRevision, "GCS_MANUAL")
        required(record.stageModel, "STAGE_MODEL")
        required(record.safetyProcedureReference, "SAFETY_PROCEDURE")
        required(record.operatorReference, "OPERATOR")
        required(record.witnessReference, "WITNESS")
        required(record.approvalDate, "APPROVAL_DATE")
        if (!record.axisMappingConfirmed) failures += "AXIS_MAPPING_NOT_VERIFIED"
        if (!record.endpointVerified) failures += "ENDPOINT_NOT_VERIFIED"
        if (!record.interlocksVerified) failures += "INTERLOCKS_NOT_VERIFIED"
        if (!record.emergencyStopVerified) failures += "EMERGENCY_STOP_NOT_VERIFIED"
        if (record.explicitlyApprovedQueries != candidateQueries)
            failures += "QUERY_ALLOWLIST_NOT_EXACT"
        return PhysicalReadOnlyPreflightVerdict(
            approvedForSeparateManualReview = failures.isEmpty(),
            reasons = failures
        )
    }
}
