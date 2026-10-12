# Recovery Snapshot Consistency Check

Offline audit revalidation now collects the current recovery report twice and requires equality before comparing an operator resolution. A difference yields INSPECTION_BLOCKED with SNAPSHOT_CHANGED_DURING_INSPECTION.

This conservative observation check is not atomic: the filesystem may change between samples or immediately afterward, and identical observations do not guarantee stable files or content authenticity. It does not approve publication, remove locks, resume a task or permit PI hardware operations. The operator gate remains in force for REQUIRE_OPERATOR evidence.
