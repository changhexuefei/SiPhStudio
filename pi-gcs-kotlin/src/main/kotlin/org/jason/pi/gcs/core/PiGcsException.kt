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
    val errorCode: Int
) : PiGcsException(
    message = "PI GCS 命令执行失败: command='$command', errorCode=$errorCode"
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