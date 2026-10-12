# Offline Recovery Resolution Audit Contract

The `PhysicalReadOnlyRecoveryResolutionAudit` API binds a non-authorizing operator note to a **snapshot** of the offline recovery report using a deterministic SHA-256 digest. The snapshot contains the recovery decision/reasons, lock presence, lock run ID and warnings, and the list of staging names. An altered snapshot invalidates the link.

Only `INVESTIGATE`, `QUARANTINE`, and `ESCALATE` dispositions are accepted. No `APPROVE`, `RESUME`, `DELETE`, or `UNLOCK` action is provided. The API does not mutate the filesystem and does not connect to or authorize physical PI equipment.

**Important limits:** The digest is not a cryptographic signature and does not authenticate an operator. A valid digest does not establish that the report is complete or race-free. Lock PID/timestamp are diagnostic and untrusted, and the snapshot does not capture full file bytes, hashes of all staging contents, or all lock fields. This record is not a durable persisted event log and does not prove post-crash survival. Independently verify evidence and require explicit human-controlled procedures for any recovery.
