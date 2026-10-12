# Offline Recovery Audit Revalidation

`PhysicalReadOnlyRecoveryAuditRevalidation.check` re-collects the current read-only recovery snapshot and checks an existing operator resolution against it. Outcomes are `MATCHES_CURRENT_SNAPSHOT`, `CHANGED_OR_INVALID`, or `INSPECTION_BLOCKED`.

A matching checksum only indicates that the latest observed diagnostic snapshot has the same bound fields. It is **not** approval, identity proof, lock ownership, a signed audit trail, automatic resume, or permission for PI commands or mechanical motion. `REQUIRE_OPERATOR` remains `REQUIRE_OPERATOR` even when the snapshot matches.

The operation uses normal filesystem reads and does **not** create a transactional snapshot. A concurrent process can modify evidence during or after inspection. If paths are invalid or evidence verification returns BLOCK, revalidation reports INSPECTION_BLOCKED. Reviewers must separately preserve and verify original artifacts.
