package org.jason.pi.gcs.core

/** PI GCS 错误分类。 */
enum class PiGcsErrorCategory {
    None,
    Motion,
    Controller,
    Communication,
    Unknown
}

/**
 * 结构化 PI GCS 错误说明。
 *
 * 这里只收录已经由 PI 官方 PIPython 文档明确给出的错误码。
 * 未收录的错误码保持原值，避免使用不可靠的猜测映射。
 */
data class PiGcsErrorDescriptor(
    val code: Int,
    val symbol: String,
    val message: String,
    val category: PiGcsErrorCategory,
    val recoveryHint: String? = null
) {
    val isError: Boolean
        get() = code != 0
}

object PiGcsErrorCatalog {

    private val knownErrors: Map<Int, PiGcsErrorDescriptor> = listOf(
        PiGcsErrorDescriptor(
            code = 0,
            symbol = "PI_NO_ERROR",
            message = "No controller error",
            category = PiGcsErrorCategory.None
        ),
        PiGcsErrorDescriptor(
            code = -1024,
            symbol = "E_1024_PI_MOTION_ERROR",
            message = "Motion error: position error too large; the servo may have been switched off automatically",
            category = PiGcsErrorCategory.Motion,
            recoveryHint = "Stop motion, inspect the mechanics and travel limits, clear the controller error, then verify servo state before retrying."
        )
    ).associateBy { it.code }

    fun describe(code: Int): PiGcsErrorDescriptor {
        return knownErrors[code] ?: PiGcsErrorDescriptor(
            code = code,
            symbol = "PI_GCS_ERROR_$code",
            message = "PI controller returned error code $code",
            category = PiGcsErrorCategory.Unknown,
            recoveryHint = "Look up this error code in the manual for the connected PI controller or the official PIPython gcserror definitions."
        )
    }
}
