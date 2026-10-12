package org.jason.siph.ui.diagnostics

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PiRecoveryDesktopFailureCategoriesTest {
    private fun failure(exception: Exception) =
        PiRecoveryOfflineInspector.inspectSamples { throw exception }

    @Test fun permissionFailureUsesAccessDenied() {
        val dto = failure(AccessDeniedException("secret/path"))
        assertEquals("ACCESS_DENIED", dto.failureCategory)
        assertEquals(1, dto.failedSample)
        assertEquals("BLOCK", dto.decision)
        assertFalse(dto.reasonCodes.any { it.contains("secret") })
    }

    @Test fun missingPathUsesPathUnavailable() {
        val dto = failure(NoSuchFileException("missing/private"))
        assertEquals("PATH_UNAVAILABLE", dto.failureCategory)
        assertEquals("INSPECTION_BLOCKED", dto.status)
    }

    @Test fun fileIOErrorUsesIOError() {
        val dto = failure(IOException("private details"))
        assertEquals("IO_ERROR", dto.failureCategory)
        assertEquals(listOf("SNAPSHOT_SAMPLE_FAILED"), dto.reasonCodes)
    }

    @Test fun securityExceptionUsesAccessDenied() {
        assertEquals("ACCESS_DENIED", failure(SecurityException("secrets")).failureCategory)
    }

    @Test fun unexpectedExceptionIsOtherError() {
        assertEquals("OTHER_ERROR", failure(IllegalStateException("private")).failureCategory)
    }
}
