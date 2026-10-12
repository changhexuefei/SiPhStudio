# Offline Recovery Inspection Diagnostics

The read-only recovery revalidation result includes optional `failureCategory` and `failedSample` fields while retaining the existing status, decision and reasons contract.

- `ACCESS_DENIED`: denied or security-restricted file inspection
- `PATH_UNAVAILABLE`: file disappeared or could not be found
- `IO_ERROR`: other file-system I/O error
- `OTHER_ERROR`: unexpected non-I/O exception
- `SNAPSHOT_CHANGED`: two observations disagree
- `NONE`: no categorized collection failure

Sampling failures remain `INSPECTION_BLOCKED` with decision `BLOCK` and reason `SNAPSHOT_SAMPLE_FAILED`. The 1-based `failedSample` is set for the first or second failing observation, never for a snapshot mismatch. The exception message or local file paths are not copied into the report.

This is diagnostics only, not automatic remediation or physical approval. Filesystem races may remain undetected, and no PI control connection, motion or lock removal is permitted.
