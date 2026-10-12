# Offline Recovery Diagnostics UI Lifecycle Gate

The desktop panel uses a monotonic request epoch to allow only the currently active inspection to publish a result or reset its loading state. A new request supersedes an older one. Input changes invalidate the epoch, clear output and cancel the keyed Compose effect. Disposal explicitly invalidates the epoch. Kotlin/JVM unit tests exercise competing completions, edit invalidation, disposal and sequential refresh.

This is not a physical filesystem cancellation guarantee. Blocking I/O may finish after coroutine cancellation; the epoch gate prevents that obsolete result from becoming visible. The panel is offline and read-only; it never removes publication locks, grants approvals or sends PI motion commands.
