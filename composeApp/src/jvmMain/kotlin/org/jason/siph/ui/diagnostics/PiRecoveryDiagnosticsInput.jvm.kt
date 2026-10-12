package org.jason.siph.ui.diagnostics

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * UI boundary for a local, read-only evidence location. No directory creation,
 * canonical path traversal, unlock, hardware operation, or automatic recovery.
 */
internal object PiRecoveryDiagnosticsInput {
    data class Validated(val parent: Path, val publishedName: String)
    enum class Error { PARENT_REQUIRED, NAME_REQUIRED, INVALID_NAME, INVALID_PATH, INVALID_PARENT }

    fun validate(parentText: String, nameText: String): Pair<Validated?, Error?> {
        if (parentText.isBlank()) return null to Error.PARENT_REQUIRED
        if (nameText.isBlank()) return null to Error.NAME_REQUIRED
        // Do not trim a name: silently changing the destination could inspect
        // a different evidence bundle than the operator specified.
        if (nameText != nameText.trim() || nameText == "." || nameText == ".." ||
            nameText.length > 200 || nameText.contains('/') || nameText.contains('\\') ||
            nameText.any { Character.isISOControl(it) } || nameText.endsWith('.')) {
            return null to Error.INVALID_NAME
        }
        val parent = try {
            Path.of(parentText).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return null to Error.INVALID_PATH
        } catch (_: SecurityException) {
            return null to Error.INVALID_PATH
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return null to Error.INVALID_PARENT
        }
        return Validated(parent, nameText) to null
    }
}
