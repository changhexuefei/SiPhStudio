# Offline Publication Recovery Report v1

`PhysicalReadOnlyPublicationRecoveryReport.collect(parent, publishedName, record)` combines the existing fail-closed startup decision, presence of a target-specific `*.publish.lock`, untrusted read-only lock diagnostics, and a sorted inventory of `.pi-readonly-staging-*` names.

The report is a snapshot for **operator review**. It never deletes files, steals a lock, concludes a process is dead from a PID, authorizes approval, or reconnects to PI hardware. Existing publishing and recovery entry points remain unchanged.

An existing lock or orphan staging triggers `REQUIRE_OPERATOR`. Invalid parent paths, unsafe names or directory listing errors are `BLOCK`. Valid evidence is reviewable only under the existing bundle verification contract; there is no automatic resume. Reports are not signed or persisted, and a concurrent writer can change files during collection. This is not forensic-grade chain-of-custody or power-loss durability evidence.
