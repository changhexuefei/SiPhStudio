# Desktop Recovery Diagnostics Input Safety

The JVM-only recovery diagnostics screen validates its input before any inspection:
- The parent must be an existing directory (without following a symbolic link at the final component).
- The published directory name must be a single, nonblank path component, without separators, control characters, leading/trailing whitespace, a trailing dot, or an overlong name.
- Invalid values render only a stable INSPECTION_BLOCKED error code. Raw paths and filesystem exception messages are not displayed.
- Editing input clears the prior displayed result, so stale evidence is not left visible after changing the inspection target.

Validation is a UI convenience and does not make path inspection atomic, prevent ancestor symlink replacement, sign evidence, prove approval, or authorize PI motion. The recovery backend remains fail-closed. The panel never deletes files or releases publication locks.