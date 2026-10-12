# Offline Publication Lock Audit v1

A cooperative lock at `<destination>.publish.lock` is created with atomic CREATE_NEW. It now contains a diagnostic schema with `version=1`, a random `runId` UUID, local `pid` and `createdUtc` timestamp. These fields are **untrusted metadata**, not authentication or proof that a process is still running.

`PhysicalReadOnlyPublicationLockAudit.read(path)` is read-only and bounded to 4096 bytes. Unknown schemas, malformed fields, missing files or oversized files produce warnings. It never steals, repairs or deletes locks and does not authorize recovery. The existing `PhysicalReadOnlyEvidenceRecovery.inspect` continues to return REQUIRE_OPERATOR whenever the target lock exists, regardless of parsed contents.

Limitations: file creation and metadata writing are not a single atomic operation; another observer can briefly see an empty lock. A crash can leave a partial lock. The format intentionally fails closed. Power-loss durability, tamper authentication, race-free publication against non-cooperating writers and physical PI hardware approval are not provided.