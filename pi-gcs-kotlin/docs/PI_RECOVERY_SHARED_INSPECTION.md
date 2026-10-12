# Shared offline recovery sampling contract

The desktop diagnostic inspector delegates double-sampling, exception classification and fail-closed decisions to `PhysicalReadOnlyRecoveryAuditRevalidation.inspectWithoutResolution`. A public `inspectWithCollector` seam permits deterministic JVM contract tests without touching files or hardware.

An unsigned/no-resolution inspection **never** emits MATCHES_CURRENT_SNAPSHOT. Stable non-BLOCK observations produce CHANGED_OR_INVALID; stable BLOCK observations remain INSPECTION_BLOCKED. Snapshot disagreement and sample exceptions remain blocked with the shared failure categories and sample index.

No locks are removed, evidence files modified, recovery granted or PI controllers contacted. Two filesystem reads cannot prove an atomic snapshot.
