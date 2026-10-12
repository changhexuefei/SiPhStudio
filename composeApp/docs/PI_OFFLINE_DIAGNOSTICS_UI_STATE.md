# Desktop Offline Recovery UI State Consistency

The Compose Desktop diagnostic screen performs filesystem inspection using a keyed `LaunchedEffect` with `Dispatchers.IO` instead of blocking the UI thread. Every Inspect click creates a unique request with an immutable copy of its parent/name inputs. Editing either input clears the request and previous output. Switching away from the diagnostics panel disposes the effect and cancels its outstanding request.

Cancellation is rethrown rather than shown as a filesystem fault. Only an active request's outcome can be displayed after `withContext` returns to the UI dispatcher. Repeated inspections clear older results and the inspect button is disabled while an active scan is running.

Cancellation does **not** guarantee immediate termination of a blocking filesystem read: the job can continue on an I/O worker until the call returns. This is UI stale-result suppression, not an atomic filesystem snapshot, crash-durability proof, PI equipment authorization, or any form of automatic recovery.
