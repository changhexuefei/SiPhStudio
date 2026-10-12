# PI Offline Evidence Durability Capability (v1)

This is a scoped, read-only file-system probe. It never connects to a PI controller or authorizes GCS commands, servo, or mechanical motion.

`PhysicalReadOnlyEvidenceDurability.probeFileSync(path)` attempts `FileChannel.force(true)` for an existing regular file opened read-only, preserving its bytes. `VERIFIED` means only that the provider returned successfully from that call; it does **not** establish durable directory entries or prove storage survived power loss. `FAILED` reports the exception type; `NOT_ATTEMPTED` means the path is not an ordinary file (including missing files and symbolic links).

Every report keeps `directorySync=NOT_VERIFIED` and `powerLossDurability=NOT_VERIFIED`. The existing publisher is unchanged and performs neither an integrated pre-publish data-sync sequence nor a verified parent-directory sync. Windows and other platforms may behave differently; CI results are not hardware or crash-recovery acceptance.

Before upgrading the durability claim, implement and test a platform-specific write-channel force for every evidence file, metadata persistence, parent directory synchronization where supported, failure injection, and restart/power-loss validation. Unsupported operations must produce an explicit failure rather than silent success.
