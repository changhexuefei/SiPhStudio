# Signed/unsigned recovery sampling parity

The Kotlin recovery audit with an operator resolution and the unsigned desktop inspection share the same two-sample engine. This regression suite verifies that stable BLOCK reports, report changes, and first- or second-sample exceptions produce identical fail-closed results.

For stable REQUIRE_OPERATOR observations, signed revalidation may report MATCHES_CURRENT_SNAPSHOT only when a non-authorizing documented resolution matches; unsigned inspection must remain CHANGED_OR_INVALID. A tampered operator disposition cannot turn either route into automatic authorization.

Each failure type is tested at both sample indices: AccessDeniedException, NoSuchFileException, IOException, SecurityException, and an unexpected runtime exception. Exception paths/messages must never be serialized into recovery reason codes.

These tests construct in-memory reports only. Snapshot matching is not permission to remove publication locks, resume operations or move a PI controller.
