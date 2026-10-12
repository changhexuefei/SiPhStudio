# Offline Evidence Publication Lock Contract

The public `PhysicalReadOnlyEvidencePublisher.publish` now atomically creates a sibling file named `<destination>.publish.lock` before generating evidence, and deletes the lock after publishing or handling an exception.

- Two cooperating publishers using the same absolute, normalized destination cannot hold the same CREATE_NEW lock simultaneously. An occupied lock fails immediately; there is no implicit retry or lock stealing.
- If the process crashes, a stale lock can remain. Operators must inspect it together with any orphaned staging directory; it is **not** automatically removed.
- Lock-deletion failure is surfaced (or added as a suppressed I/O exception when publication already failed).
- This is *cooperative* path-level exclusion, not a proof of OS-level no-replace atomic rename. Non-cooperating software and filesystem-level races are outside the guarantee. Atomic move and file-sync durability limitations remain unchanged.
- This feature is restricted to offline evidence files. It does not authenticate approval or authorize a PI hardware connection or any motion.
