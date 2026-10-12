package org.jason.siph.ui.diagnostics

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PiRecoveryDiagnosticsInputTest {
    @Test fun existingDirectoryAndSimpleNameAreAccepted() {
        val root = Files.createTempDirectory("pi-diagnostics-input")
        try {
            val (valid, error) = PiRecoveryDiagnosticsInput.validate(root.toString(), "approved")
            assertNull(error)
            assertEquals(root.toAbsolutePath().normalize(), assertNotNull(valid).parent)
            assertEquals("approved", valid.publishedName)
        } finally {
            Files.delete(root)
        }
    }

    @Test fun unsafeNamesAreBlocked() {
        val root = Files.createTempDirectory("pi-diagnostics-name")
        try {
            for (name in listOf("../escape", "sub/bundle", "..", ".", " bundle", "bundle ",
                "bundle.", "line\nfeed", "sub\\bundle", "x".repeat(201))) {
                val (valid, error) = PiRecoveryDiagnosticsInput.validate(root.toString(), name)
                assertNull(valid)
                assertEquals(PiRecoveryDiagnosticsInput.Error.INVALID_NAME, error)
            }
        } finally {
            Files.delete(root)
        }
    }

    @Test fun missingInputAndNonexistentParentBlock() {
        assertEquals(PiRecoveryDiagnosticsInput.Error.PARENT_REQUIRED,
            PiRecoveryDiagnosticsInput.validate(" ", "approved").second)
        assertEquals(PiRecoveryDiagnosticsInput.Error.NAME_REQUIRED,
            PiRecoveryDiagnosticsInput.validate(".", " ").second)
        val root = Files.createTempDirectory("pi-diagnostics-missing")
        try {
            val missing = root.resolve("nonexistent").toString()
            assertEquals(PiRecoveryDiagnosticsInput.Error.INVALID_PARENT,
                PiRecoveryDiagnosticsInput.validate(missing, "approved").second)
        } finally {
            Files.delete(root)
        }
    }
}
