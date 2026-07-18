package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis

/** 可被探测或推断的 PI 控制器能力。 */
enum class PiGcsFeature {
    Identify,
    VersionQuery,
    AxisDiscovery,
    PositionQuery,
    OnTargetQuery,
    ServoQuery,
    TravelLimitQuery,
    AbsoluteMove,
    RelativeMove,
    ServoControl,
    Stop,
    ReferenceFRF,
    ReferenceFNL,
    ReferenceFPL
}

enum class PiCapabilityStatus {
    Supported,
    Unsupported,
    Assumed,
    NotProbed
}

data class PiCapabilityResult(
    val feature: PiGcsFeature,
    val status: PiCapabilityStatus,
    val detail: String? = null
) {
    val isAvailable: Boolean
        get() = status == PiCapabilityStatus.Supported ||
            status == PiCapabilityStatus.Assumed
}

data class PiControllerCapabilities(
    val results: Map<PiGcsFeature, PiCapabilityResult>
) {

    operator fun get(feature: PiGcsFeature): PiCapabilityResult {
        return results[feature] ?: PiCapabilityResult(
            feature = feature,
            status = PiCapabilityStatus.NotProbed
        )
    }

    fun supports(feature: PiGcsFeature): Boolean = get(feature).isAvailable

    val supportedFeatures: Set<PiGcsFeature>
        get() = results.values
            .filter { it.isAvailable }
            .mapTo(linkedSetOf()) { it.feature }
}

data class PiControllerProfile(
    val info: PiControllerInfo,
    val axisIds: List<PiAxisId>,
    val capabilities: PiControllerCapabilities
) {
    val knownHexapodAxes: List<PiAxis>
        get() = axisIds.mapNotNull { it.knownHexapodAxis }
}

/**
 * 控制器探测选项。
 *
 * 默认只执行 *IDN?、VER? 和 SAI?。轴状态查询需要额外网络往返，
 * 因此由 [probeAxisQueries] 显式开启。
 */
data class PiControllerProbeOptions(
    val connectionType: PiConnectionType = PiConnectionType.Unknown,
    val probeAxisQueries: Boolean = false,
    val assumedReferenceModes: Set<PiReferenceCommand> = emptySet()
)

/**
 * 读取控制器信息并建立能力快照。
 *
 * 这里只探测只读命令，不会发送 MOV/MVR/SVO/FRF/FNL/FPL，避免设备在连接阶段运动。
 * 每个探测查询读取响应后都会在同一事务中执行 ERR?，避免把控制器的错误响应误判为支持。
 * 写命令能力根据对应只读能力保守推断，参考模式必须由设备配置显式声明。
 */
suspend fun GcsDevice.inspectController(
    options: PiControllerProbeOptions = PiControllerProbeOptions()
): PiControllerProfile {
    val capabilityResults = linkedMapOf<PiGcsFeature, PiCapabilityResult>()

    val idn = checkedQuery(GcsCommand.qIDN()).trim()
    capabilityResults.supported(PiGcsFeature.Identify, "*IDN? succeeded with ERR?=0")
    capabilityResults.assumed(PiGcsFeature.Stop, "STP is part of the configured PI control path")

    val versionResult = runCatching {
        checkedQuery(GcsCommand.qVER()).trim()
    }
    val versionText = versionResult.getOrNull()
    capabilityResults.fromResult(
        feature = PiGcsFeature.VersionQuery,
        result = versionResult
    )

    val axesResult = runCatching {
        GcsResponseParser.parseAxisIds(
            checkedQuery(GcsCommand.qAxes())
        )
    }
    val axisIds = axesResult.getOrDefault(emptyList())
    capabilityResults.fromResult(
        feature = PiGcsFeature.AxisDiscovery,
        result = axesResult
    )

    if (options.probeAxisQueries && axisIds.isNotEmpty()) {
        val positionResult = runCatching {
            GcsResponseParser.parseAxisIdDoubleMap(
                response = checkedQuery(GcsCommand.qPositionIds(axisIds)),
                expectedAxes = axisIds
            )
        }
        capabilityResults.fromResult(PiGcsFeature.PositionQuery, positionResult)

        val onTargetResult = runCatching {
            GcsResponseParser.parseAxisIdBooleanMap(
                response = checkedQuery(GcsCommand.qOnTargetIds(axisIds)),
                expectedAxes = axisIds
            )
        }
        capabilityResults.fromResult(PiGcsFeature.OnTargetQuery, onTargetResult)

        val servoResult = runCatching {
            GcsResponseParser.parseAxisIdBooleanMap(
                response = checkedQuery(GcsCommand.qServoIds(axisIds)),
                expectedAxes = axisIds
            )
        }
        capabilityResults.fromResult(PiGcsFeature.ServoQuery, servoResult)

        val travelResult = runCatching {
            val minimums = GcsResponseParser.parseAxisIdDoubleMap(
                response = checkedQuery(GcsCommand.qTravelMinIds(axisIds)),
                expectedAxes = axisIds
            )
            val maximums = GcsResponseParser.parseAxisIdDoubleMap(
                response = checkedQuery(GcsCommand.qTravelMaxIds(axisIds)),
                expectedAxes = axisIds
            )
            minimums to maximums
        }
        capabilityResults.fromResult(PiGcsFeature.TravelLimitQuery, travelResult)

        if (positionResult.isSuccess) {
            capabilityResults.assumed(
                PiGcsFeature.AbsoluteMove,
                "MOV inferred from successful checked POS? support"
            )
            capabilityResults.assumed(
                PiGcsFeature.RelativeMove,
                "MVR inferred from successful checked POS? support"
            )
        } else {
            capabilityResults.notProbed(
                PiGcsFeature.AbsoluteMove,
                "Write commands are never executed during inspection"
            )
            capabilityResults.notProbed(
                PiGcsFeature.RelativeMove,
                "Write commands are never executed during inspection"
            )
        }

        if (servoResult.isSuccess) {
            capabilityResults.assumed(
                PiGcsFeature.ServoControl,
                "SVO setter inferred from successful checked SVO? support"
            )
        } else {
            capabilityResults.notProbed(
                PiGcsFeature.ServoControl,
                "Write commands are never executed during inspection"
            )
        }
    } else {
        listOf(
            PiGcsFeature.PositionQuery,
            PiGcsFeature.OnTargetQuery,
            PiGcsFeature.ServoQuery,
            PiGcsFeature.TravelLimitQuery,
            PiGcsFeature.AbsoluteMove,
            PiGcsFeature.RelativeMove,
            PiGcsFeature.ServoControl
        ).forEach { feature ->
            capabilityResults.notProbed(
                feature,
                "Axis query probing is disabled or no axes were discovered"
            )
        }
    }

    PiReferenceCommand.entries.forEach { mode ->
        val feature = mode.toFeature()
        if (mode in options.assumedReferenceModes) {
            capabilityResults.assumed(
                feature,
                "Declared by the application configuration; not executed during inspection"
            )
        } else {
            capabilityResults.notProbed(
                feature,
                "Reference commands are motion-producing and are never probed automatically"
            )
        }
    }

    val knownAxes = axisIds.mapNotNull { it.knownHexapodAxis }
    val raw = buildMap {
        put("*IDN?", idn)
        versionText?.let { put("VER?", it) }
        if (axisIds.isNotEmpty()) {
            put("SAI?", axisIds.joinToString(" ") { it.value })
        }
    }

    val info = PiControllerInfo.fromRaw(
        idn = idn,
        versionText = versionText,
        axes = knownAxes,
        connectionType = options.connectionType,
        raw = raw
    )

    return PiControllerProfile(
        info = info,
        axisIds = axisIds,
        capabilities = PiControllerCapabilities(capabilityResults.toMap())
    )
}

private suspend fun GcsDevice.checkedQuery(command: GcsCommand): String {
    require(command.isQuery) {
        "Controller inspection only accepts query commands: ${command.text}"
    }
    return requireNotNull(executeChecked(command)) {
        "PI GCS checked query 未返回响应: ${command.text}"
    }
}

private fun PiReferenceCommand.toFeature(): PiGcsFeature {
    return when (this) {
        PiReferenceCommand.FRF -> PiGcsFeature.ReferenceFRF
        PiReferenceCommand.FNL -> PiGcsFeature.ReferenceFNL
        PiReferenceCommand.FPL -> PiGcsFeature.ReferenceFPL
    }
}

private fun MutableMap<PiGcsFeature, PiCapabilityResult>.supported(
    feature: PiGcsFeature,
    detail: String? = null
) {
    this[feature] = PiCapabilityResult(
        feature = feature,
        status = PiCapabilityStatus.Supported,
        detail = detail
    )
}

private fun MutableMap<PiGcsFeature, PiCapabilityResult>.assumed(
    feature: PiGcsFeature,
    detail: String? = null
) {
    this[feature] = PiCapabilityResult(
        feature = feature,
        status = PiCapabilityStatus.Assumed,
        detail = detail
    )
}

private fun MutableMap<PiGcsFeature, PiCapabilityResult>.notProbed(
    feature: PiGcsFeature,
    detail: String? = null
) {
    this[feature] = PiCapabilityResult(
        feature = feature,
        status = PiCapabilityStatus.NotProbed,
        detail = detail
    )
}

private fun MutableMap<PiGcsFeature, PiCapabilityResult>.fromResult(
    feature: PiGcsFeature,
    result: Result<*>
) {
    this[feature] = PiCapabilityResult(
        feature = feature,
        status = if (result.isSuccess) {
            PiCapabilityStatus.Supported
        } else {
            PiCapabilityStatus.Unsupported
        },
        detail = result.exceptionOrNull()?.message
    )
}
