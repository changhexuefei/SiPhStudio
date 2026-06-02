package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis

/**
 * PI 控制器运行模式。
 */
enum class PiControllerRunMode {

    /**
     * 真实硬件控制器。
     */
    Real,

    /**
     * PI 官方仿真 / 虚拟控制器。
     */
    Simulation,

    /**
     * 由上层软件模拟的控制器。
     */
    Demo,

    /**
     * 暂时无法判断。
     */
    Unknown
}

/**
 * PI 控制器连接类型。
 */
enum class PiConnectionType {
    TcpIp,
    Usb,
    Serial,
    Gpib,
    Dll,
    Simulation,
    Demo,
    Unknown
}

/**
 * PI GCS 版本类型。
 */
enum class PiGcsVersion {
    Gcs2,
    Gcs3,
    Unknown
}

/**
 * PI 控制器信息。
 *
 * 这个类只保存控制器相关信息，不负责发送 GCS 命令。
 */
data class PiControllerInfo(

    /**
     * *IDN? 返回值。
     */
    val idn: String,

    /**
     * VER? 返回值。
     */
    val versionText: String? = null,

    /**
     * 控制器名称。
     *
     * 可以从 idn 中解析，也可以由调用者指定。
     */
    val controllerName: String? = null,

    /**
     * 控制器型号。
     *
     * 例如 C-887、C-887.52、E-727 等。
     */
    val controllerModel: String? = null,

    /**
     * 控制器序列号。
     */
    val serialNumber: String? = null,

    /**
     * 固件版本。
     */
    val firmwareVersion: String? = null,

    /**
     * GCS 协议版本。
     */
    val gcsVersion: PiGcsVersion = PiGcsVersion.Unknown,

    /**
     * 连接类型。
     */
    val connectionType: PiConnectionType = PiConnectionType.Unknown,

    /**
     * 运行模式。
     */
    val runMode: PiControllerRunMode = PiControllerRunMode.Unknown,

    /**
     * 轴列表。
     *
     * Hexapod 通常是 X/Y/Z/U/V/W。
     */
    val axes: List<PiAxis> = emptyList(),

    /**
     * 是否是 Hexapod。
     */
    val isHexapod: Boolean = false,

    /**
     * 是否是仿真控制器。
     */
    val isSimulation: Boolean = false,

    /**
     * 原始信息。
     *
     * 用于保存额外的 GCS 查询结果，方便调试。
     */
    val raw: Map<String, String> = emptyMap()
) {

    val axesText: String
        get() = axes.joinToString(" ") { it.code }

    val displayName: String
        get() {
            return controllerModel
                ?: controllerName
                ?: idn.takeIf { it.isNotBlank() }
                ?: "Unknown PI Controller"
        }

    val isRealHardware: Boolean
        get() = runMode == PiControllerRunMode.Real && !isSimulation

    companion object {

        fun unknown(
            idn: String = ""
        ): PiControllerInfo {
            return PiControllerInfo(
                idn = idn,
                runMode = PiControllerRunMode.Unknown,
                gcsVersion = PiGcsVersion.Unknown,
                connectionType = PiConnectionType.Unknown
            )
        }

        fun demo(
            axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
        ): PiControllerInfo {
            return PiControllerInfo(
                idn = "Demo PI Hexapod Controller",
                versionText = "Demo",
                controllerName = "Demo PI Hexapod",
                controllerModel = "DEMO",
                gcsVersion = PiGcsVersion.Gcs2,
                connectionType = PiConnectionType.Demo,
                runMode = PiControllerRunMode.Demo,
                axes = axes,
                isHexapod = true,
                isSimulation = true
            )
        }

        fun fromRaw(
            idn: String,
            versionText: String? = null,
            axes: List<PiAxis> = emptyList(),
            connectionType: PiConnectionType = PiConnectionType.Unknown,
            raw: Map<String, String> = emptyMap()
        ): PiControllerInfo {
            val parsed = PiIdnParser.parse(idn)
            val simulation = detectSimulation(idn, versionText)
            val hexapod = detectHexapod(idn, axes)
            val gcsVersion = detectGcsVersion(versionText)

            return PiControllerInfo(
                idn = idn,
                versionText = versionText,
                controllerName = parsed.controllerName,
                controllerModel = parsed.controllerModel,
                serialNumber = parsed.serialNumber,
                firmwareVersion = parsed.firmwareVersion,
                gcsVersion = gcsVersion,
                connectionType = connectionType,
                runMode = if (simulation) {
                    PiControllerRunMode.Simulation
                } else {
                    PiControllerRunMode.Real
                },
                axes = axes,
                isHexapod = hexapod,
                isSimulation = simulation,
                raw = raw
            )
        }

        private fun detectSimulation(
            idn: String,
            versionText: String?
        ): Boolean {
            val text = listOfNotNull(
                idn,
                versionText
            ).joinToString(" ")

            return text.contains("SIM", ignoreCase = true) ||
                    text.contains("SIMULATION", ignoreCase = true) ||
                    text.contains("VIRTUAL", ignoreCase = true) ||
                    text.contains("PIVIRTUAL", ignoreCase = true)
        }

        private fun detectHexapod(
            idn: String,
            axes: List<PiAxis>
        ): Boolean {
            val text = idn.uppercase()

            if ("HEXAPOD" in text) {
                return true
            }

            val axisSet = axes.toSet()

            return PiAxis.HEXAPOD_AXES.all { it in axisSet }
        }

        private fun detectGcsVersion(
            versionText: String?
        ): PiGcsVersion {
            val text = versionText.orEmpty()

            return when {
                text.contains("GCS3", ignoreCase = true) ||
                        text.contains("GCS 3", ignoreCase = true) -> {
                    PiGcsVersion.Gcs3
                }

                text.contains("GCS2", ignoreCase = true) ||
                        text.contains("GCS 2", ignoreCase = true) -> {
                    PiGcsVersion.Gcs2
                }

                else -> {
                    PiGcsVersion.Unknown
                }
            }
        }
    }
}

/**
 * *IDN? 解析结果。
 *
 * 注意：
 * 不同 PI 控制器的 *IDN? 格式可能不完全一致，
 * 所以这里采用保守解析，解析不到就返回 null。
 */
data class PiIdnParts(
    val controllerName: String? = null,
    val controllerModel: String? = null,
    val serialNumber: String? = null,
    val firmwareVersion: String? = null
)

/**
 * PI *IDN? 解析器。
 */
object PiIdnParser {

    fun parse(
        idn: String
    ): PiIdnParts {
        val text = idn.trim()

        if (text.isBlank()) {
            return PiIdnParts()
        }

        val parts = text
            .split(",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val model = findModel(text)
        val serial = findSerial(text)
        val firmware = findFirmware(text)

        val name = when {
            parts.isNotEmpty() -> parts.first()
            else -> null
        }

        return PiIdnParts(
            controllerName = name,
            controllerModel = model,
            serialNumber = serial,
            firmwareVersion = firmware
        )
    }

    private fun findModel(
        text: String
    ): String? {
        val modelRegex = Regex(
            pattern = """\b[A-Z]-\d{3}(?:\.\d+)?\b"""
        )

        return modelRegex.find(text)?.value
    }

    private fun findSerial(
        text: String
    ): String? {
        val explicitRegex = Regex(
            pattern = """(?:SN|S/N|Serial|Serial\s*No\.?)\s*[:=]?\s*([A-Za-z0-9\-]+)""",
            option = RegexOption.IGNORE_CASE
        )

        val explicit = explicitRegex.find(text)?.groupValues?.getOrNull(1)
        if (!explicit.isNullOrBlank()) {
            return explicit
        }

        return null
    }

    private fun findFirmware(
        text: String
    ): String? {
        val firmwareRegex = Regex(
            pattern = """(?:FW|Firmware|Version|Ver\.?)\s*[:=]?\s*([A-Za-z0-9\.\-_]+)""",
            option = RegexOption.IGNORE_CASE
        )

        return firmwareRegex.find(text)?.groupValues?.getOrNull(1)
    }
}