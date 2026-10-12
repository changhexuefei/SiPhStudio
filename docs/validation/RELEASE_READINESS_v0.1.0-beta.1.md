# SiPhStudio v0.1.0-beta.1 Release Readiness

## Scope and freeze

This is a **developer beta / Windows read-only preview**, not a released or safety-qualified PI six-axis production controller. Following PR #133, recovery diagnostics DTO v1 is frozen for this baseline. No new recovery diagnostic features, hardware motion exposure, or DTO-breaking changes belong in this release. Fixes require an explicit safety/regression justification.

Desktop Physical mode permits operator-initiated Connect, `*IDN?`, `POS?` and Disconnect. It does not expose real Move/Jog/Stop, automatic alignment, servo enable, homing, or automatic recovery. Never infer motion safety or production authorization from read-only CI or diagnostics.

## Release gates

| Gate | Evidence | Acceptance |
| --- | --- | --- |
| Source baseline | Merge SHA on master and unique `v0.1.0-beta.1` tag | Exact release commit recorded; refuse existing tag/release |
| JVM tests | Windows JDK21 `:pi-gcs-kotlin:test :composeApp:jvmTest` | Both pass in same workflow run |
| MSI package | `:composeApp:packageMsi`, packageVersion `0.1.0` | One Windows MSI produced with SHA-256 |
| Installed-image validation | `msiexec /a` to isolated directory | Administrative extraction exits 0 |
| Packaged startup | Launcher from extracted MSI | Starts and remains running for six seconds in CI, then force-terminated **as a test process only** |
| Evidence | `release-readiness-evidence.txt`, MSI, test reports | Uploaded together, SHA/commit included |
| Operational restrictions | README and release notes | Unvalidated live PI motion and automatic recovery explicitly excluded |
| Official PI emulator | Separate supervised compatibility checklist | **NOT RUN** for beta, required before `v0.1.0` |
| Physical PI controller | Controlled site acceptance | **NOT RUN** for beta; required for any later relevant production claim |

Note: `msiexec /a` verifies administrative extraction and packaged launcher startup, **not** a full interactive machine-wide installation, upgrade or uninstall. Those require separate acceptance on a Windows test system before `v0.1.0`.

## Release procedure

1. Merge the readiness PR only after its own GitHub Actions `Release Readiness Gate` completes successfully.
2. On `master`, run `Release Readiness Gate` via `workflow_dispatch` with `publish_release=false` to verify the exact post-merge commit. Inspect test reports, MSI and SHA-256.
3. After a successful dry run, run the same workflow on `master` with `publish_release=true`. The workflow retests, builds and smoke-launches the installer **before** creating tag `v0.1.0-beta.1` and a prerelease with the MSI and evidence attached.
4. Never move or overwrite a released tag. Roll back by publishing a new corrective prerelease, not by rewriting the original tag.
5. Document and separately verify installer installation/uninstallation, official emulator read-only protocol, and real PI read-only site acceptance before `v0.1.0`.

## Future milestones

- `v0.1.0`: Windows installed-package smoke and uninstall evidence; official PI emulator read-only protocol tests; an explicit, audited release acceptance record. Physical motion remains disabled and not validated.
- `v1.0.0`: target-controller on-site evidence for the actually released operational scope, including handling of communication loss, limits, interlocks, emergency stop, and recovery; never claim a safety function without real-world verification.

## Stop-ship conditions

Any red CI check, missing MSI/evidence, mismatched hash or source SHA, unstable packaged startup, accidentally enabled Physical Move/Jog/Stop, undocumented behavior change or scope expansion blocks prerelease publication. Hardware acceptance cannot be marked PASS using virtual tests.
