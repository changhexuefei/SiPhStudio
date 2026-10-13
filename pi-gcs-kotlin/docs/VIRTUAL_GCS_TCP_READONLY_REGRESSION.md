# Virtual GCS TCP read-only regression

The repository already contains real loopback TCP tests for fragmented GCS responses, EOF, timeouts and reconnection. This increment exercises **two independent TCP sessions**, each performing one `*IDN?` and three six-axis `POS?` reads, using the production `KtorTcpGcsTransport`, `GcsClient` and `GcsDevice` stack.

The isolated localhost server has a strict command allowlist. It rejects anything other than `*IDN?` and `POS? X Y Z U V W` and records every command, then tests assert the exact expected sequence. This is a JVM CI fixture, not an operator-accessible PI controller emulator and not a physical motion safety component. It must never be used to authorize real six-axis movements.

Run: `./gradlew :pi-gcs-kotlin:test`. Windows JDK21 CI executes this test without installing PI software.

**Evidence category: VIRTUAL_GCS_TCP**, not PIHexapodEmulator. Official PIHexapodEmulator compatibility remains `NOT_RUN`; `v0.1.0` stable publication remains blocked until separate reviewed, supervised official read-only protocol results exist. No edits to the published `v0.1.0-beta.1` tag or Release.
