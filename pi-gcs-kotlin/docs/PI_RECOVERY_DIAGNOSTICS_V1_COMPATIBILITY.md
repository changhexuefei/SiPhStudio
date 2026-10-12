# Offline Recovery Diagnostics v1 Contract Hardening

Schema version remains **1** and all existing fields and valid representations stay unchanged. Consumers reject contradictory sampling metadata:

- ACCESS_DENIED, PATH_UNAVAILABLE, IO_ERROR and OTHER_ERROR require a first/second sample number, SNAPSHOT_SAMPLE_FAILED reason, INSPECTION_BLOCKED status and BLOCK decision.
- SNAPSHOT_CHANGED requires SNAPSHOT_CHANGED_DURING_INSPECTION, INSPECTION_BLOCKED, BLOCK, and no failed sample number.
- A sample index cannot appear without an actual sampling-failure category.
- A failure-specific reason cannot be paired with failureCategory=NONE.

The validation function reports stable error codes rather than exposing file names or exception text. Invalid DTOs remain in the UI's existing fail-closed presentation / copy fallback. Valid operator-review records are not converted to approvals. This is read-only metadata validation and does not eliminate filesystem TOCTOU.
