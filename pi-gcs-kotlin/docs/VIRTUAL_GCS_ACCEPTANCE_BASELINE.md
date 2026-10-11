# Virtual PI GCS acceptance baseline (software only)

This document describes **software-only** validation for the read-only PI GCS loopback simulator in SiPhStudio. Passing these checks is **not** certification of PI hardware, its firmware, motion accuracy, servo functions, interlocks, or personnel safety.

## Required automated acceptance (Windows / JDK 21)

The workflow `.github/workflows/pi-gcs-kotlin.yml` runs these checks for changes to `pi-gcs-kotlin/**` and relevant Gradle files:

| Gate | Mechanism | Required outcome |
| --- | --- | --- |
| Kotlin protocol and safety tests | `:pi-gcs-kotlin:test` | Green |
| Timeout and recovery soak | 20 independent virtual TCP server instances | Each timeout is followed by clean new-session read-only queries |
| Client/worker lifecycle | Simulator `activeClientCount` and `liveWorkerCount` | Both 0 after server shutdown |
| Per-cycle scenario evidence | `VirtualGcsEvidenceContract.validateScenario` | Timeout occurs exactly once; query/session lifecycle valid |
| Strict evidence aggregation | `VirtualGcsSoakSummary.aggregate` | `aggregate-summary.json` PASS, 20/20 cycles |
| Provenance | Per-cycle `provenance.json` | Run ID, commit SHA and SHA-256 of summary/trace match |
| Independent artifact gate | `.github/scripts/verify-pi-gcs-evidence.ps1` | Green before artifact upload |
| Gate mutation regressions | `.github/scripts/test-pi-gcs-evidence-gate.ps1` | Original passes; 4 modified copies are rejected |

The workflow uploads `pi-gcs-test-reports` with JUnit reports and `pi-gcs-kotlin/build/ci/virtual-gcs-evidence/` when files are available. Artifact upload runs even when validation fails, for diagnosis; **artifact presence alone does not imply PASS**.

## Evidence review

1. Open the successful Windows/JDK21 workflow run for the exact PR head SHA.
2. Confirm the Gradle test step, mutation gate, and independent verifier all succeeded.
3. Download `pi-gcs-test-reports` and inspect `recovery-soak/aggregate-summary.json`.
4. Confirm `source=VIRTUAL_ONLY`, `result=PASS`, `expectedCycles=20`, `passedCycles=20`, `failedCycles=0`, and zero failures.
5. Confirm the run ID and revision refer to that workflow and every `cycle-NN` contains `summary.json`, `trace.jsonl`, `provenance.json`.
6. Treat missing artifacts, cancelled runs, a stale revision or failing checks as **not accepted**.

## Explicit exclusions and remaining work

| Item | Status |
| --- | --- |
| Local, virtual, read-only `*IDN?`, `POS?`, `ERR?` protocol | Covered by software test suite |
| Virtual disconnect/timeout/reconnect, stale sessions, journaling | Covered by software test suite |
| Actual PI six-axis controller TCP connectivity and official PI emulator | **NOT VALIDATED** by this baseline |
| Real motion, servo enable/disable, homing and travel limits | **NOT VALIDATED; do not infer authorization to move** |
| Safety interlocks, collision avoidance, emergency stop | **NOT VALIDATED** |
| Long-duration hardware soak, positioning accuracy or calibration | **NOT VALIDATED** |

Physical qualification must be planned and approved separately by the equipment owner, including a documented controller model, firmware revision, a motion-free read-only preflight, and a subsequent independent mechanically supervised safety procedure. No CI green check authorizes live motion.

## Next-stage physical-readonly preparation

See [PI_PHYSICAL_READONLY_PREFLIGHT.md](PI_PHYSICAL_READONLY_PREFLIGHT.md) for the equipment-owner approval record, read-only query boundaries and explicit NO GO rules. This documentation does not grant hardware access or authorize servo/motion commands. Physical controller connectivity remains **NOT TESTED**.
