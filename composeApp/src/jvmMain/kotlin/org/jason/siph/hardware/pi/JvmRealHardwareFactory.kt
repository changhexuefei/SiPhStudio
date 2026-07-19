package org.jason.siph.hardware.pi

import org.jason.pi.gcs.core.GcsClient
import org.jason.pi.gcs.core.GcsDevice
import org.jason.pi.gcs.core.PiConnectionType
import org.jason.pi.gcs.core.PiControllerProbeOptions
import org.jason.pi.gcs.hexapod.AngularCommandUnit
import org.jason.pi.gcs.hexapod.LinearCommandUnit
import org.jason.pi.gcs.hexapod.PiGcsHexapodPort
import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.hexapod.PiHexapodUnitConfig
import org.jason.pi.gcs.transport.KtorTcpGcsTransport
import org.jason.siph.di.RealHardwarePorts
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.persistence.JvmJsonAutonomyRepository
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * 在 JVM 启动阶段组装真实 PI TCP/GCS 端口和本地工作流数据库。
 *
 * 数据库在 Demo/Real 模式下都启用，默认路径：
 * ~/.siphstudio/autonomy-workflow.json
 */
fun createJvmRealHardwarePorts(
    runtimeMode: HardwareRuntimeMode
): RealHardwarePorts {
    val autonomyRepository = JvmJsonAutonomyRepository(resolveAutonomyDatabasePath())

    if (runtimeMode != HardwareRuntimeMode.Real) {
        return RealHardwarePorts(autonomyRepositories = autonomyRepository)
    }

    val host = System.getProperty(PROPERTY_HOST)?.trim().orEmpty()
    if (host.isEmpty()) {
        return RealHardwarePorts(autonomyRepositories = autonomyRepository)
    }

    val config = PiJvmConnectionConfig.fromSystemProperties(host)
    val transport = KtorTcpGcsTransport(
        host = config.host,
        port = config.port,
        timeout = config.timeoutMs.milliseconds
    )
    val device = GcsDevice(
        GcsClient(
            transport = transport,
            checkErrorAfterCommand = true
        )
    )
    val piPort = PiGcsHexapodPort(
        device = device,
        safePose = config.safePose,
        unitConfig = PiHexapodUnitConfig(
            linearCommandUnit = config.linearCommandUnit,
            angularCommandUnit = AngularCommandUnit.Degree
        ),
        probeOptions = PiControllerProbeOptions(
            connectionType = PiConnectionType.TcpIp
        )
    )

    return RealHardwarePorts(
        positioner = PiOpticalPositionerAdapter(piPort),
        autonomyRepositories = autonomyRepository
    )
}

private fun resolveAutonomyDatabasePath(): Path {
    val configuredDirectory = System.getProperty(PROPERTY_DATA_DIRECTORY)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val directory = configuredDirectory?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".siphstudio")
    return directory.resolve(AUTONOMY_DATABASE_FILE)
}

private data class PiJvmConnectionConfig(
    val host: String,
    val port: Int,
    val timeoutMs: Long,
    val linearCommandUnit: LinearCommandUnit,
    val safePose: PiHexapodPose
) {
    companion object {
        fun fromSystemProperties(host: String): PiJvmConnectionConfig {
            return PiJvmConnectionConfig(
                host = host,
                port = readIntProperty(PROPERTY_PORT, DEFAULT_PORT) { value ->
                    value in 1..65_535
                },
                timeoutMs = readLongProperty(PROPERTY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS) { value ->
                    value > 0L
                },
                linearCommandUnit = parseLinearUnit(System.getProperty(PROPERTY_LINEAR_UNIT)),
                safePose = parseSafePose(
                    requireNotNull(System.getProperty(PROPERTY_SAFE_POSE)) {
                        "$PROPERTY_SAFE_POSE is required when $PROPERTY_HOST is configured. " +
                            "Use xUm,yUm,zUm,uDeg,vDeg,wDeg with verified fixture values."
                    }
                )
            )
        }
    }
}

private fun parseSafePose(raw: String): PiHexapodPose {
    val values = raw.split(',').map { it.trim() }
    require(values.size == 6) {
        "$PROPERTY_SAFE_POSE must contain exactly 6 comma-separated values: " +
            "xUm,yUm,zUm,uDeg,vDeg,wDeg"
    }

    val numbers = values.mapIndexed { index, text ->
        text.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: error("$PROPERTY_SAFE_POSE value[$index] is not a finite number: '$text'")
    }

    return PiHexapodPose(
        xUm = numbers[0],
        yUm = numbers[1],
        zUm = numbers[2],
        uDeg = numbers[3],
        vDeg = numbers[4],
        wDeg = numbers[5]
    )
}

private fun parseLinearUnit(raw: String?): LinearCommandUnit {
    return when (raw?.trim()?.lowercase().orEmpty()) {
        "", "mm", "millimeter", "millimetre" -> LinearCommandUnit.Millimeter
        "um", "µm", "micrometer", "micrometre" -> LinearCommandUnit.Micrometer
        else -> error("$PROPERTY_LINEAR_UNIT must be 'mm' or 'um', actual='$raw'")
    }
}

private fun readIntProperty(
    name: String,
    defaultValue: Int,
    validator: (Int) -> Boolean
): Int {
    val raw = System.getProperty(name) ?: return defaultValue
    val value = raw.toIntOrNull() ?: error("$name must be an integer, actual='$raw'")
    require(validator(value)) { "$name is outside the allowed range: $value" }
    return value
}

private fun readLongProperty(
    name: String,
    defaultValue: Long,
    validator: (Long) -> Boolean
): Long {
    val raw = System.getProperty(name) ?: return defaultValue
    val value = raw.toLongOrNull() ?: error("$name must be a long integer, actual='$raw'")
    require(validator(value)) { "$name is outside the allowed range: $value" }
    return value
}

private const val PROPERTY_HOST = "siph.pi.host"
private const val PROPERTY_PORT = "siph.pi.port"
private const val PROPERTY_TIMEOUT_MS = "siph.pi.timeoutMs"
private const val PROPERTY_LINEAR_UNIT = "siph.pi.linearUnit"
private const val PROPERTY_SAFE_POSE = "siph.pi.safePose"
private const val PROPERTY_DATA_DIRECTORY = "siph.data.dir"
private const val AUTONOMY_DATABASE_FILE = "autonomy-workflow.json"
private const val DEFAULT_PORT = 50_000
private const val DEFAULT_TIMEOUT_MS = 5_000L
