# JVM Desktop Offline Recovery Diagnostics (first UI integration)

The desktop window exposes an **Offline recovery diagnostics** toggle, separate from the normal coupling screen. It accepts a local evidence parent directory and published directory name, reads the existing recovery report using a NULL approval record, translates the result to Recovery Diagnostics DTO v1 and renders status, decision, reason codes, failure category and failed sample.

The screen never declares MATCHES_CURRENT_SNAPSHOT, as no operator audit resolution has been supplied or verified. A result other than BLOCK is shown as CHANGED_OR_INVALID, which **does not** indicate an error with the file itself; it means that the screen has not verified an operator audit. Validating an evidence bundle requires separate approved metadata.

No backend state is mutated. The panel has no connection to PI hardware and contains no unlock, delete, resume, or motion action. Local path entry is only used for offline inspection; raw exceptions and paths are not exposed in diagnostics output. It is JVM-only and does not add PI GCS code to Wasm common source sets.

Future work: add explicit operator audit inputs and report stability guarantees before any richer workflow; retain external approvals and manual lock recovery.
