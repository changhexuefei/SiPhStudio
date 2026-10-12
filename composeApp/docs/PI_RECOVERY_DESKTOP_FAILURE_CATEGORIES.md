# Desktop Offline Recovery Failure Categories

The desktop double-sampling adapter exports stable failure categories with no raw exception messages or paths:

- `ACCESS_DENIED`: `AccessDeniedException` or `SecurityException`
- `PATH_UNAVAILABLE`: `NoSuchFileException`
- `IO_ERROR`: other `IOException`
- `OTHER_ERROR`: other sampling exception
- `SNAPSHOT_CHANGED`: two completed recovery reports disagree

The first or second failing sample is included as 1-based `failedSample`; a changed report has no failing sample index. All failures export `INSPECTION_BLOCKED` and `BLOCK`, with unchanged `SNAPSHOT_SAMPLE_FAILED` or `SNAPSHOT_CHANGED_DURING_INSPECTION` reason codes.

These diagnostics do not establish a filesystem atomic snapshot or authorize recovery. The UI never commands a PI controller, clears a lock, modifies evidence, or resumes operations.
