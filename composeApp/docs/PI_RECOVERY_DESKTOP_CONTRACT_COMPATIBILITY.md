# Desktop Recovery Diagnostics Consumer Compatibility Gate

The Compose Desktop JVM tests exercise the end-to-end, **purely offline** contract between `pi-gcs-kotlin` Recovery Diagnostics DTO v1 and its desktop inspector, presentation adapter, and sanitized support-copy formatter.

The matrix covers stable REQUIRE_OPERATOR evidence, first-sample access denial, second-sample missing paths, I/O exceptions, changed observations, contradictory sampling metadata, and an unsupported future schema.

For valid data, the UI preserves the fail-closed decision and diagnostic reason. The support copy contains only the explicit machine-readable allowlist. For invalid or future-schema data, both presentation and copy use INVALID_DIAGNOSTIC_CONTRACT rather than displaying untrusted fields.

No integration test invokes a PI controller, filesystem lock release, staged-file deletion, approval, resume or motion. Two filesystem samples are not atomic.
