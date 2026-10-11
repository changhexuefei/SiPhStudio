# PI hexapod physical controller: read-only preflight (NOT EXECUTED)

> **Status: NOT TESTED ON HARDWARE.** CI validates only the virtual localhost GCS server. This document is a preparation checklist, not authority to connect to or move real equipment.

## Scope and safety boundaries

- A qualified equipment owner must approve any future live connection, define a safe operating state, and verify hardware interlocks and emergency-stop access using the PI manual for the exact model.
- Initial preflight must never send servo enable/disable, home/reference, move, stop, reset, initialization, firmware/configuration writes, or commands of unknown side effects.
- Only allowlisted queries, subject to verification against the **exact** controller GCS firmware manual: `*IDN?`, `ERR?`, and `POS? X Y Z U V W`. If the controller does not support or guarantee these as non-moving read-only queries, **abort**.
- No continuous polling, auto-reconnect on real hardware, retries of unknown-outcome writes, or use of simulated safety evidence as physical evidence.
- Transport address, cabling, axes and operational state must be independently verified before any live session. Store logs without credentials or personal data.

## Required approval record (blank until performed)

| Field | Required entry |
| --- | --- |
| Equipment owner / authorization | NOT PROVIDED |
| PI controller model and serial number | NOT PROVIDED |
| Firmware / GCS dialect / manual revision | NOT PROVIDED |
| Six-axis stage model and axis naming map | NOT VERIFIED |
| Supported Ethernet/GCS endpoint | NOT VERIFIED |
| Approved physical lab safety procedure | NOT PROVIDED |
| Interlock and emergency-stop verification | NOT PERFORMED |
| Operator, witness and date | NOT PROVIDED |
| Approved exact read-only queries | NOT APPROVED |

**Go/no-go rule:** missing any required approval or uncertainty about any command means **NO GO**. No automated CI, simulator report, or code review replaces approval.

## Suggested non-operational evidence contract

If a separately authorized physical-readonly preflight is performed later, record:

- `source=PHYSICAL_READONLY`, `result=PASS|FAIL|NOT_RUN` (never `VIRTUAL_ONLY`).
- Controller model, serial redacted/handled according to organization policy, firmware, manual revision and approved command allowlist.
- Timestamp, operator-approved test case ID, commit SHA, elapsed time, transport type, expected versus observed identity and six axis values.
- Exact read-only commands attempted; errors, timeout/cancel behavior, and explicit connection closure.
- Separate outcomes for connectivity, read-only command compatibility, safe disconnection, and unknowns.
- `motionSafety=NOT_TESTED` and `motionAuthorization=false` for every read-only report.

Physical connection PASS must **never** be represented as motion safety qualification. Motion procedures require a separately approved, supervised plan outside this preflight.

## Handoff checklist

1. Obtain the exact PI product documentation and controller GCS command reference for the deployed firmware.
2. Have the equipment owner review the allowlist and document physical safety precautions, including exclusion zones and interlock responsibilities.
3. Record a non-motion baseline, keep motion/write APIs disabled, and plan an operator-triggered one-shot read-only probe without automated fallback.
4. Review the resulting physical-readonly evidence independently from the CI `VIRTUAL_ONLY` artifacts.
5. Escalate any mismatch or unknown controller response; never infer motion readiness.

Current repository status: virtual loopback acceptance is established; **real PI hardware has not been connected or tested by this PR**.
