# Offline Recovery Snapshot Sampling Failures

The read-only recovery audit uses two filesystem observations. If either collector invocation raises an Exception (including an I/O error during the second sample), revalidation returns `INSPECTION_BLOCKED`, `BLOCK`, and `SNAPSHOT_SAMPLE_FAILED` instead of accepting or propagating a partial result.

This is a conservative audit decision; it does not repair evidence, remove locks, retry silently, authorize hardware, or provide atomic snapshot/durability guarantees. Errors such as JVM fatal failures are not intercepted.
