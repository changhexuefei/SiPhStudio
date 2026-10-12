# Offline Recovery Diagnostics Export v1

`PhysicalReadOnlyRecoveryDiagnosticsExport.export(result)` produces a versioned, JSON-compatible DTO for a UI or operator tool. The fields are `schemaVersion` (1), `status`, `decision`, `reasonCodes`, `failureCategory`, and nullable 1-based `failedSample`.

Consumers must call `validate(dto)` before using externally loaded diagnostics. This checks supported status, decision, category, reason-code syntax/length, sample index, and selected contradictory combinations. The DTO is a diagnostic record only. It deliberately contains no filesystem paths, raw exception text, credentials, controller addresses, or operation commands.

The DTO does not make a snapshot atomic, prove an operator's identity, certify disk durability, permit lock cleanup, approve testing, or authorize PI controller commands or mechanical motion. `MATCHES_CURRENT_SNAPSHOT` is informational only; the underlying `REQUIRE_OPERATOR` gate remains in force.
