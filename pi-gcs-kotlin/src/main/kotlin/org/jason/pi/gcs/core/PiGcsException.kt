package org.jason.pi.gcs.core

/**
 * PI GCS 异常。
 */
open class PiGcsException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * PI 控制器返回 ERR? 非 0 时抛出的异常。
 */
class PiGcsCommandException(
    val command: String,
    val errorCode: Int,
    val descriptor: PiGcsErrorDescriptor = PiGcsErrorCatalog.describe(errorCode)
) : PiGcsException(
    message = buildString {
        append("PI GCS 命令执行失败: command='")
        append(command)
        append("', errorCode=")
        append(errorCode)
        append(", symbol=")
        append(descriptor.symbol)
        append(", message=")
        append(descriptor.message)
        descriptor.recoveryHint?.let { hint ->
            append(", recoveryHint=")
            append(hint)
        }
    }
)

/**
 * PI GCS 响应解析失败。
 */
class PiGcsParseException(
    val response: String,
    message: String
) : PiGcsException(
    message = "$message, response='$response'"
)

/**
 * PI GCS 等待到位超时。
 */
class PiGcsTimeoutException(
    message: String
) : PiGcsException(message)
