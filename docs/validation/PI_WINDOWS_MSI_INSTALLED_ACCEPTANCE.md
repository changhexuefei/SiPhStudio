# Windows MSI installed-package acceptance — v0.1.0-beta.1

Status: **NOT RUN** until the dedicated Windows hosted Actions job passes.

This is a real installer smoke test against the **already published** immutable beta asset, not an extracted administrative MSI image. The workflow downloads the released MSI and readiness evidence, checks the SHA-256 digest, installs with msiexec /i on a disposable GitHub-hosted Windows runner, starts the installed desktop launcher for six seconds, terminates that test process, uninstalls via msiexec /x, and verifies the installed executable no longer exists.

Evidence: `install-acceptance/install.log`, `uninstall.log`, and `summary.txt`. A failed install, missing installed launcher, premature process exit, failed uninstall, or leftover installed executable is a stop-ship condition for **v0.1.0**. The cleanup runs in a PowerShell finally block after successful installation.

This workflow intentionally refuses to install on self-hosted developer or production machines. It does not clear PI publication locks or connect to an actual PI controller. Emulator testing remains NOT RUN and real mechanical motion remains NOT VALIDATED.

Full interactive install, Start Menu shortcut, GUI usability, upgrades, permissions across user profiles, antivirus interactions and per-user data retention need separate manual acceptance; a six-second launcher smoke is not evidence for those scenarios. MSI logs may contain local runner paths and should be reviewed before external sharing.

For v0.1.0, require this test to pass and complete the separate official PI emulator read-only matrix before the non-beta release.
