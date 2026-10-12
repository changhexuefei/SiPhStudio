# PI official emulator v0.1.0 acceptance gate

**Status: NOT RUN.** The released beta MSI passed Windows installation and uninstall acceptance in PR #135. That does not establish PI official emulator interoperability.

A supervised operator must install a licensed PIHexapodEmulator on a dedicated, isolated Windows test machine. Before connecting SiPhStudio, independently verify simulator identity, TCP endpoint, controller model, GCS version and axis naming with PI tools. The application default port is not evidence of the emulator's actual TCP service.

In Physical mode perform only Connect → `*IDN?` → `POS?` → repeated Refresh → Disconnect → reconnect. Compare six-axis position mapping with PI's emulator readback: X/Y/Z in µm and U/V/W in degrees. Record expected timeout and stale-readback handling using a simulator or isolated network test service only. No MOV, MVR, SVO, homing, servo enable, STP or recovery actuation in this scope.

Copy `pi-official-emulator-evidence.template.json` into the controlled test evidence store and fill every field from **observed** runs. Keep responses/screenshots, version info, timestamps and a reviewer reference in the controlled store; avoid committing hostnames, IPs, serial numbers or private captures. Run:

```powershell
./.github/scripts/verify-pi-official-emulator-evidence.ps1 -EvidencePath <approved-record.json> -RequirePass
```

The JSON template is intentionally `NOT_RUN`, and the CI negative test asserts it cannot pass `-RequirePass`. Template validation does **not** run the official emulator or prove the operator's claims. Release managers must independently inspect referenced evidence.

`v0.1.0` is blocked until all required official simulator read-only checks have real PASS evidence, and the standalone Windows installer/desktop tests remain green. Real PI controller and motion safety acceptance remain separate, unvalidated stages. Never auto-enable Physical motion after emulator acceptance.
