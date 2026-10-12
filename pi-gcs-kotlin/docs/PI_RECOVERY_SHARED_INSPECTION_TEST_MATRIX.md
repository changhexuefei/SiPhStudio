# Shared Recovery Inspection Regression Matrix

The shared `PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithCollector` entry point is consumed by both the PI GCS recovery layer and Compose Desktop offline diagnostics. Deterministic JVM tests cover stable blocked reports, changed reason sets between samples, all supported exception categories, second-sample failures, and every decision under unsigned/no-resolution inspection.

Required invariants:
- Two successful identical reports produce a decision-specific **non-approved** diagnostic; unsigned inspection never reports `MATCHES_CURRENT_SNAPSHOT`.
- Disagreeing samples always return `INSPECTION_BLOCKED`, `BLOCK` and `SNAPSHOT_CHANGED`.
- Exceptions return fail-closed sample-indexed metadata with no raw path/message leakage.
- Exported diagnostic DTO v1 passes contract validation.

These tests have no hardware, network, filesystem mutation or automatic recovery. They do not prove atomic snapshot collection.
