# Shared Recovery Sampling Engine

The signed operator resolution revalidation and the unsigned desktop inspection now call the same private two-observation sampler. It owns sample ordering, exception classification, and snapshot-change blocking.

The callers preserve different semantics:
- Unsigned desktop inspection never reports MATCHES_CURRENT_SNAPSHOT, even for stable evidence.
- Signed resolution revalidation may report a match only after verifying the supplied resolution against a stable non-BLOCK report.
- Stable BLOCK remains blocked in both cases, and either sampling error or changed evidence stops both paths before any verification.

No public DTO schema changes. This is read-only evidence inspection and not atomic filesystem snapshotting or authorization to remove locks, resume or move PI hardware.
