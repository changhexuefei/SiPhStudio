# Recovery Diagnostics Visual Status

JVM desktop diagnostics now maps validated recovery decisions to distinct visual tones alongside unambiguous text.

- BLOCK: blocked (error tone)
- REQUIRE_OPERATOR: operator action (tertiary tone)
- VALIDATE_FOR_MANUAL_REVIEW: manual review required (secondary tone)
- NO_BUNDLE: informational (neutral surface tone)
- Invalid DTO or input: blocked

This is a display-only categorization. It never introduces a success/approved state; colors are redundant with text and do not authorize PI controller access, mechanical motion, lock removal, automatic resume or evidence modification.
