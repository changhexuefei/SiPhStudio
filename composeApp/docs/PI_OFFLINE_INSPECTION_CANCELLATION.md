# Offline Recovery Inspection Cancellation

The JVM-only diagnostic panel shows progress during filesystem inspection and exposes an explicit **Cancel inspection** control. Cancellation clears any previous diagnostic output, invalidates the epoch of the active request, cancels the Compose effect and displays a neutral **INSPECTION_CANCELLED** message.

A cancelled task may finish an underlying blocking filesystem call. Its late completion cannot update UI state because the request epoch is no longer current. The operator may launch a new inspection afterward. Cancellation is not a lock-release or hardware stop command; it does not modify evidence, resume tasks, authorize recovery or send PI commands.

The progress indicator is indeterminate: it does not measure remaining I/O time or assert bounded filesystem completion.
