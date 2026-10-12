# Recovery diagnostics producer-side validation

`PhysicalReadOnlyRecoveryDiagnosticsExport.export` now runs the existing v1 `validate` function against each DTO it constructs. It refuses inconsistent statuses, failure categories or failed-sample metadata rather than emitting data that UI consumers must reject.

This preserves the existing v1 schema and valid serialized values; malformed in-memory `Result` inputs throw `IllegalArgumentException` with a generic diagnostic message, not raw filesystem paths or exception contents. The shared recovery sampler continues producing valid results, while direct callers can no longer bypass the contract checks.

This is read-only metadata validation. It does not authorize recovery, clear locks, resume tests, or command PI hardware.
