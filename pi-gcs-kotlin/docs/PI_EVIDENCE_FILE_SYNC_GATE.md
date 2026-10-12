# PI Offline Evidence Pre-Publication File Sync Gate

The publisher writes and validates the three staged files (`approval.json`, `integrity.json`, `bundle.json`), opens each regular file with a write channel, calls `FileChannel.force(true)` in that order, then attempts `ATOMIC_MOVE` without a non-atomic fallback.

Any file force failure aborts publication and invokes existing staging cleanup. Tests inject failed synchronization and verify the destination is not created. Synchronization is checked before the pre-move callback.

**Limits:** A successful `force(true)` means only that the filesystem provider reported success. The staging directory and destination's parent directory are not explicitly synchronized. Cross-filesystem, power-loss and hardware crash consistency have not been validated. This PR makes no durable-rename or power-loss survival guarantee. No PI controller connection or motion is performed.