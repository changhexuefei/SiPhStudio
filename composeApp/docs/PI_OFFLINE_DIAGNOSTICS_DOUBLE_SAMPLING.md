# Desktop Recovery Diagnostics Double Sampling

The JVM desktop's offline inspector now collects the read-only recovery report twice before showing a diagnostic result. If observations differ, it exports `INSPECTION_BLOCKED / BLOCK / SNAPSHOT_CHANGED`. If either sample fails, it exports `INSPECTION_BLOCKED / BLOCK / SNAPSHOT_SAMPLE_FAILED` with the failed sample index. Raw exception paths/messages are never included.

Stable observations still yield `CHANGED_OR_INVALID` because this screen does not collect or verify an operator resolution. A stable `REQUIRE_OPERATOR` decision remains an operator gate, not an approval.

These two reads are not atomic and cannot detect all filesystem races or prove disk durability. No evidence mutation, lock deletion, PI hardware connection, automatic resume or motion is involved.
