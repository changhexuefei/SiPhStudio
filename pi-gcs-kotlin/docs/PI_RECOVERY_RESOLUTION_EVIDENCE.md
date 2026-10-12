# PI Offline Recovery Resolution Evidence v1

`PhysicalReadOnlyRecoveryResolutionEvidence.capture(report, operatorReference, caseReference, disposition, recordedUtc)` creates an immutable in-memory audit snapshot with the original recovery decision and reasons, lock presence and diagnostic run UUID, staging directory names, operator and case references, timestamp and disposition.

Disposition is restricted to INVESTIGATE, REJECT or MANUAL_REVIEW_ONLY. MANUAL_REVIEW_ONLY can only be captured from a VALIDATE_FOR_MANUAL_REVIEW decision. It never grants access to equipment, removes locks, repairs files, resumes jobs or approves PI motion.

`validate(record)` rechecks schema, references, timestamp, disposition and SHA-256. The digest is a deterministic integrity checksum of length-prefixed UTF-8 fields; **it is not a digital signature, authentication, or proof of operator identity**. A malicious party who can change both fields and digest can forge an apparently valid record. The snapshot is not automatically persisted, and file-system evidence can change after capture; manual handling must preserve original files and use independent approval controls.
