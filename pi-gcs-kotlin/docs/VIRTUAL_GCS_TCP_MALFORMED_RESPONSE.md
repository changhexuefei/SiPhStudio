# Virtual GCS TCP malformed response regression

The local loopback Socket fixture exercises the production Ktor TCP GCS client with malformed six-axis `POS?` responses. Cases include a nonnumeric Z coordinate and a duplicated V coordinate where W is missing. Neither may be accepted as a complete six-axis position.

The server accepts **only** `POS? X Y Z U V W`, with no write-capable or physical motion GCS command, and runs without PI hardware or the vendor emulator. These negative tests augment earlier TCP disconnection and truncated-response tests, and do not authorize physical motion or recovery. Run `./gradlew :pi-gcs-kotlin:test` on Windows/JDK21.

Evidence class: `VIRTUAL_GCS_TCP`; official PIHexapodEmulator read-only compatibility remains **NOT_RUN**, and `v0.1.0` stable release remains blocked pending supervised and independently reviewed vendor emulator evidence.
