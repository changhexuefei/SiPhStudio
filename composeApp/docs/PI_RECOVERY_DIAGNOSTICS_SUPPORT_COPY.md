# Sanitized Offline Recovery Diagnostic Copy

The JVM-only recovery diagnostics screen offers an explicit **Copy sanitized summary** action after a completed or failed inspection. Nothing is copied automatically. The support text includes an allowlist: contract marker, status, decision, reason codes, failure category, failed sample, and READ_ONLY=true.

Raw filesystem paths, entered directory names, exception text, device addresses, operator identities and lock contents are intentionally excluded. Invalid DTOs become INSPECTION_BLOCKED / BLOCK with INVALID_DIAGNOSTIC_CONTRACT; unexpected errors become INSPECTION_FAILED. Clipboard failures show a generic notice.

Copying a diagnostic is not an approval or recovery command. Clipboard contents are visible to applications allowed to read the user's clipboard, so the user should review before sharing. No evidence files are written, no publication lock is cleared and no PI controller is contacted.
