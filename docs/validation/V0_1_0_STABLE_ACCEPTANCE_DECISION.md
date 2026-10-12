# SiPhStudio v0.1.0 stable acceptance decision

Status: **BLOCKED / NOT RELEASED** pending real PIHexapodEmulator read-only validation.

| Gate | Evidence | Current |
| --- | --- | --- |
| Frozen read-only beta | GitHub v0.1.0-beta.1 tag, MSI and release SHA report | PASS |
| Windows hosted MSI real install/start/uninstall | PR #135 Windows MSI Installation Acceptance artifact | CI PASS |
| Official PIHexapodEmulator read-only TCP protocol | Reviewed supervised evidence with all required checks PASS | NOT RUN / BLOCKED |
| Physical hardware acceptance | Separate on-device read-only acceptance | NOT RUN; not a claim for this desktop stable scope |
| Real physical motion safety | Dedicated on-site interlock/limit/e-stop/recovery validation | NOT VALIDATED; movement remains disabled |

The `v0.1.0 Stable Acceptance Preflight` workflow deliberately checks that the released beta and its installer evidence exist and that the repository's placeholder official emulator evidence is **rejected** by the strict verifier. A green *preflight contract* does not signal release authorization. The workflow never creates tags or a GitHub Release.

Before contemplating a stable release:
1. Run the officially installed PIHexapodEmulator on an isolated authorised Windows setup and execute Connect, `*IDN?`, `POS?`, unit comparison, refresh, disconnect, reconnect and controlled timeout observation.
2. Complete the JSON template with real observed evidence, a concrete 40-character Git revision, versions, review signoff and a controlled evidence reference. Do **not** replace unexecuted cases with PASS merely to satisfy CI.
3. Independently review observations, screenshots and logs; execute the strict verifier with `-RequirePass` against that approved record.
4. Retest Windows installer / JVM CI at the candidate commit. Only then propose an independent stable release workflow and `v0.1.0` tag. Never move the existing beta tag.

Warning: the evidence verifier checks the record's schema and declared statuses; it cannot prove that a PI-branded emulator was genuinely used. Human review is mandatory. Physical motion and automatic recovery remain outside the release scope.
