# Recovery Diagnostics v1 Export Invariant Gate

The signed audit-revalidation and unsigned desktop inspection paths share a two-sample collector. This in-memory regression suite tests their end-to-end export into `PhysicalReadOnlyRecoveryDiagnosticsExport.Dto` schema version 1.

The matrix checks each stable unsigned recovery decision (never a verified audit match), changed observations (INSPECTION_BLOCKED/BLOCK/SNAPSHOT_CHANGED), all five sampling-exception classes at first and second sample positions, a valid non-authorizing signed operator note, and a tampered note. Every exported DTO must pass the v1 validator, preserve diagnostic categories and failed sample indices, and exclude private exception paths.

A signed snapshot match means only that the non-authorizing resolution note corresponds to observed evidence. It never grants publication, lock release, automatic resume, or PI hardware motion. Double-sampling detects some races but is not an atomic filesystem snapshot. The tests do not access real files or devices.
