# Official PI emulator evidence negative contract

This deterministic CI suite protects the v0.1.0 stable-release stop-ship decision. It does **not** run PIHexapodEmulator.

Cases cover NOT_RUN checks, fabricated PASS checks with an unverified Git revision, placeholder PI emulator metadata despite a valid-looking revision, a false Physical motion validation claim, and missing required six-axis observation.

Each case must be rejected by the strict `-RequirePass` evidence validator. Green CI demonstrates that malformed or incomplete declarations fail closed, **not** that PI official emulator interoperability has passed. Only a supervised real emulator session and independently reviewed read-only TCP evidence can remove the NOT_RUN block. Physical Move/Jog/Servo/Home and automatic recovery remain disabled and outside scope.
