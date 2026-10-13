# PIHexapodEmulator on-site read-only integration runbook

**Current vendor status: NOT RUN.** This checklist is for a **future authorized PIHexapodEmulator installation**, not a claim that one is installed or approved. The PI Software Suite download request may require support review; don't circumvent vendor access controls or replace official PI software with an unofficial copy.

## Before the session

1. Request PI Software Suite C-990.CD1 and PIHexapodEmulator C-887 documentation through PI China. Keep request/approval details privately; never commit credentials, license keys or download URLs requiring authentication.
2. On an isolated Windows test machine, follow the version-specific PI installation manual, including any QEMU requirements. Confirm the emulator model, GCS dialect, axis naming and **actual** TCP listener address/port with PI tools; do **not** infer port 50000 from the application's default.
3. Install the released SiPhStudio read-only Windows application, record the installed version and Git revision, and confirm physical motion UI remains disabled.
4. Use the optional **network-only** preflight below. It initiates one TCP connection, sends **zero** GCS commands and cannot establish protocol compatibility:

```powershell
./scripts/pi-emulator-connectivity-preflight.ps1 -HostName <verified-emulator-host> -Port <verified-emulator-port> -OutputPath ./local-connectivity.json
```

The preflight report intentionally omits the endpoint's address; preserve any site-specific connectivity details in a separate controlled record. **Do not upload hostnames, IP addresses, login information or unredacted captures to public CI artifacts.**

## Supervised read-only session

With the vendor emulator verified, use SiPhStudio Physical mode: manually Connect; query `*IDN?`; query `POS?` for X/Y/Z/U/V/W; repeat Refresh; Disconnect; reconnect. Compare all six reported axes and units with PI's own emulator reference. Record the actual raw response format privately (single-line/multiline), TCP timeout outcomes, errors, screenshots and software/firmware version details. Use only controlled virtual networking for timeout experiments.

Never send `MOV`, `MVR`, `SVO`, homing, referencing or `STP` from this acceptance flow. Disconnect alone is not an emergency-stop mechanism on real machines.

## Acceptance evidence

Copy `docs/validation/pi-official-emulator-evidence.template.json` to a **private** evidence store and fill it based on observed results. Use a concrete 40-hex Git commit, software versions, reviewer, evidence reference and each required read-only PASS/FAIL/NOT_RUN. Verify:

```powershell
./.github/scripts/verify-pi-official-emulator-evidence.ps1 -EvidencePath <privately-reviewed-evidence.json> -RequirePass
```

Strict schema PASS is **necessary, not sufficient**: a human reviewer must inspect logs/screenshots and verify the actual vendor simulator identity and response behavior. Only then consider removing the v0.1.0 official-emulator release blocker. No physical motor-control capability or hardware safety clearance follows from emulator read-only testing.

If PI download authorization remains unavailable, leave official emulator = **NOT_RUN**, continue Virtual GCS CI regression, and do not publish `v0.1.0` under the existing stable-acceptance policy.
