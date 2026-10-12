# Offline Physical Read-Only Evidence Recovery Contract (v1)

This contract applies only to the locally generated `PhysicalReadOnlyEvidenceBundle`. It **does not** authorize PI controller access, GCS commands, servo, or physical motion.

## Startup decisions

| Decision | Condition | Operator action |
|---|---|---|
| `NO_BUNDLE` | No target and no orphan staging | Create a new offline bundle if required |
| `VALIDATE_FOR_MANUAL_REVIEW` | Exactly a published bundle, no staging, and all hashes/schema/source-record checks pass | Review independently; never auto-resume |
| `REQUIRE_OPERATOR` | At least one `.pi-readonly-staging-*` remains | Preserve artifacts, investigate why publication was interrupted |
| `BLOCK` | Invalid paths, inaccessible directory, broken bundle, or missing/mismatched approval record | Reject evidence pending investigation |

This is a **read-only reconciliation**. It does not remove orphan staging, repair files, overwrite existing bundles or retry publication automatically.

## Durability boundary

`ATOMIC_MOVE` publishes a complete directory name when supported on the same filesystem; it does **not** prove data has reached stable media. There is currently no file or directory `fsync` guarantee, and crash/power-loss recoverability is **not validated** on Windows/JDK21. Never infer physical hardware acceptance from green simulation CI.

A future platform-specific durability implementation must separately test: force of each file; metadata flush; destination parent directory synchronization where available; failures during every force/move phase; and reconciliation after machine restart. If any durability guarantee is unavailable, report it explicitly rather than silently falling back.
