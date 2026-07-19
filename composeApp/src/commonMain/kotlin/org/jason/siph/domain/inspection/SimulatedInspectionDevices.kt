package org.jason.siph.domain.inspection

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.siph.domain.autonomy.AutonomyCapabilityState
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.oo.DeviceBackendMode
import org.jason.siph.domain.oo.DeviceDescriptor
import org.jason.siph.domain.oo.DeviceVerificationState
import org.jason.siph.domain.positioner.OpticalPose
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

@kotlinx.serialization.Serializable
data class InspectionFaultPlan(
    val failOperationCounts: Map<String, Int> = emptyMap(),
    val missingFiberFrames: Int = 0,
    val missingTargetFrames: Int = 0,
    val invalidZSamples: Int = 0,
    val cameraNoiseAmplitude: Int = 3
) {
    init {
        require(failOperationCounts.values.all { it >= 0 })
        require(missingFiberFrames >= 0)
        require(missingTargetFrames >= 0)
        require(invalidZSamples >= 0)
        require(cameraNoiseAmplitude in 0..40)
    }
}

private data class InspectionFaultState(
    val failures: MutableMap<String, Int>,
    var missingFiberFrames: Int,
    var missingTargetFrames: Int,
    var invalidZSamples: Int
)

class SimulatedInspectionEnvironment(
    private val poseProvider: suspend () -> OpticalPose,
    private val temperatureProvider: suspend () -> Double = { 25.0 },
    val targetKind: VisionFeatureKind = VisionFeatureKind.Grating,
    private val targetStageXUmAt25C: Double = 18.0,
    private val targetStageYUmAt25C: Double = -12.0,
    private val surfaceZUmAt25C: Double = -45.0,
    private val thermalShiftXUmPerC: Double = 0.12,
    private val thermalShiftYUmPerC: Double = -0.08,
    private val thermalSurfaceShiftUmPerC: Double = 0.04,
    private val truePivotZUm: Double = 92.0,
    faultPlan: InspectionFaultPlan = InspectionFaultPlan()
) {
    private val mutex = Mutex()
    private val faultState = InspectionFaultState(
        failures = faultPlan.failOperationCounts.toMutableMap(),
        missingFiberFrames = faultPlan.missingFiberFrames,
        missingTargetFrames = faultPlan.missingTargetFrames,
        invalidZSamples = faultPlan.invalidZSamples
    )
    internal val cameraNoiseAmplitude = faultPlan.cameraNoiseAmplitude

    internal suspend fun snapshot(): SimulatedInspectionSnapshot {
        val pose = poseProvider()
        val temperature = temperatureProvider()
        val deltaTemperature = temperature - 25.0
        val targetXUm = targetStageXUmAt25C + deltaTemperature * thermalShiftXUmPerC
        val targetYUm = targetStageYUmAt25C + deltaTemperature * thermalShiftYUmPerC
        val angleShiftXUm = truePivotZUm * sin(pose.vDeg * PI / 180.0)
        val angleShiftYUm = -truePivotZUm * sin(pose.uDeg * PI / 180.0)
        return SimulatedInspectionSnapshot(
            pose = pose,
            temperatureC = temperature,
            targetOffsetXUm = targetXUm - pose.xUm + angleShiftXUm,
            targetOffsetYUm = targetYUm - pose.yUm + angleShiftYUm,
            surfaceZUm = surfaceZUmAt25C + deltaTemperature * thermalSurfaceShiftUmPerC,
            targetKind = targetKind
        )
    }

    internal suspend fun beforeOperation(operation: String) {
        val shouldFail = mutex.withLock {
            val count = faultState.failures[operation] ?: 0
            if (count > 0) {
                faultState.failures[operation] = count - 1
                true
            } else {
                false
            }
        }
        if (shouldFail) error("Injected inspection failure at $operation")
    }

    internal suspend fun consumeMissingFiber(): Boolean = mutex.withLock {
        if (faultState.missingFiberFrames <= 0) false else {
            faultState.missingFiberFrames--
            true
        }
    }

    internal suspend fun consumeMissingTarget(): Boolean = mutex.withLock {
        if (faultState.missingTargetFrames <= 0) false else {
            faultState.missingTargetFrames--
            true
        }
    }

    internal suspend fun consumeInvalidZ(): Boolean = mutex.withLock {
        if (faultState.invalidZSamples <= 0) false else {
            faultState.invalidZSamples--
            true
        }
    }
}

internal data class SimulatedInspectionSnapshot(
    val pose: OpticalPose,
    val temperatureC: Double,
    val targetOffsetXUm: Double,
    val targetOffsetYUm: Double,
    val surfaceZUm: Double,
    val targetKind: VisionFeatureKind
)

class SimulatedCameraAcquisitionPort(
    private val environment: SimulatedInspectionEnvironment,
    private val nowEpochMs: () -> Long,
    initialConfig: CameraAcquisitionConfig = CameraAcquisitionConfig(
        widthPx = 160,
        heightPx = 120,
        exposureUs = 2_000.0
    )
) : CameraAcquisitionPort {
    override val descriptor = DeviceDescriptor(
        id = "sim-inspection-camera",
        vendor = "SiPhStudio",
        model = "Synthetic Vision Camera",
        backendMode = DeviceBackendMode.Simulation,
        verificationState = DeviceVerificationState.SimulationOnly
    )
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    private var connected = false
    private var streaming = false
    private var config = initialConfig
    private var frameIndex = 0L

    override suspend fun connect() {
        environment.beforeOperation("camera.connect")
        delay(5L)
        connected = true
        mutableStatus.value = readyStatus(identify())
    }

    override suspend fun disconnect() {
        streaming = false
        connected = false
        mutableStatus.value = disconnectedStatus(descriptor.model)
    }

    override suspend fun identify(): String {
        ensureConnected()
        return "SiPhStudio,Synthetic Vision Camera,0001,1.0"
    }

    override suspend fun capabilities() = CameraCapabilities(
        maximumWidthPx = 1920,
        maximumHeightPx = 1200,
        minimumExposureUs = 10.0,
        maximumExposureUs = 1_000_000.0,
        supportsHardwareTrigger = true,
        supportsStreaming = true
    )

    override suspend fun configure(config: CameraAcquisitionConfig) {
        ensureConnected()
        environment.beforeOperation("camera.configure")
        require(config.widthPx <= capabilities().maximumWidthPx)
        require(config.heightPx <= capabilities().maximumHeightPx)
        require(config.exposureUs in capabilities().minimumExposureUs..capabilities().maximumExposureUs)
        this.config = config
    }

    override suspend fun capture(): CameraFrame {
        ensureConnected()
        environment.beforeOperation("camera.capture")
        val snapshot = environment.snapshot()
        val hideFiber = environment.consumeMissingFiber()
        val hideTarget = environment.consumeMissingTarget()
        val width = config.widthPx
        val height = config.heightPx
        val pixels = ByteArray(width * height)
        val opticalCenterX = width / 2.0
        val opticalCenterY = height / 2.0
        val umPerPixel = 1.0
        val targetCenterX = opticalCenterX + snapshot.targetOffsetXUm / umPerPixel
        val targetCenterY = opticalCenterY + snapshot.targetOffsetYUm / umPerPixel

        for (y in 0 until height) {
            for (x in 0 until width) {
                val deterministicNoise = if (environment.cameraNoiseAmplitude == 0) 0 else {
                    (((x * 31L + y * 17L + frameIndex * 13L) %
                        (environment.cameraNoiseAmplitude * 2 + 1)) - environment.cameraNoiseAmplitude).toInt()
                }
                pixels[y * width + x] = (18 + deterministicNoise).coerceIn(0, 255).toByte()
            }
        }

        if (!hideTarget) {
            when (snapshot.targetKind) {
                VisionFeatureKind.Grating -> drawGrating(
                    pixels,
                    width,
                    height,
                    targetCenterX,
                    targetCenterY,
                    snapshot.pose.wDeg
                )
                VisionFeatureKind.Facet -> drawFacet(
                    pixels,
                    width,
                    height,
                    targetCenterX,
                    targetCenterY,
                    12.0 + snapshot.pose.wDeg
                )
                VisionFeatureKind.FiberTip -> Unit
            }
        }
        if (!hideFiber) drawFiberTip(pixels, width, height, opticalCenterX, opticalCenterY)

        frameIndex++
        return CameraFrame(
            frameId = "sim-frame-$frameIndex",
            capturedAtEpochMs = nowEpochMs(),
            widthPx = width,
            heightPx = height,
            strideBytes = width,
            pixelFormat = CameraPixelFormat.Gray8,
            pixels = pixels,
            exposureUs = config.exposureUs,
            gainDb = config.gainDb,
            sourceDescription = "Synthetic ${snapshot.targetKind} scene at ${snapshot.temperatureC} C"
        )
    }

    override suspend fun startStreaming() {
        ensureConnected()
        streaming = true
    }

    override suspend fun stopStreaming() {
        streaming = false
    }

    private fun ensureConnected() = check(connected) { "Simulation camera is not connected" }
}

class SimulatedZDisplacementSensorPort(
    private val environment: SimulatedInspectionEnvironment,
    private val nowEpochMs: () -> Long
) : ZDisplacementSensorPort {
    override val descriptor = DeviceDescriptor(
        id = "sim-z-displacement",
        vendor = "SiPhStudio",
        model = "Synthetic Z Displacement Sensor",
        backendMode = DeviceBackendMode.Simulation,
        verificationState = DeviceVerificationState.SimulationOnly
    )
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    private var connected = false
    private var zeroOffsetUm = 0.0
    private var sampleIndex = 0L

    override suspend fun connect() {
        environment.beforeOperation("zSensor.connect")
        connected = true
        mutableStatus.value = readyStatus(identify())
    }

    override suspend fun disconnect() {
        connected = false
        mutableStatus.value = disconnectedStatus(descriptor.model)
    }

    override suspend fun identify(): String {
        ensureConnected()
        return "SiPhStudio,Synthetic Z Displacement Sensor,0001,1.0"
    }

    override suspend fun capabilities() = ZDisplacementCapabilities(
        minimumDisplacementUm = -1_000.0,
        maximumDisplacementUm = 1_000.0,
        resolutionUm = 0.01,
        maximumSampleRateHz = 5_000.0,
        supportsHardwareZero = true
    )

    override suspend fun zero() {
        ensureConnected()
        environment.beforeOperation("zSensor.zero")
        val snapshot = environment.snapshot()
        zeroOffsetUm = snapshot.pose.zUm - snapshot.surfaceZUm
    }

    override suspend fun sample(): ZDisplacementSample {
        ensureConnected()
        environment.beforeOperation("zSensor.sample")
        if (environment.consumeInvalidZ()) {
            return ZDisplacementSample(
                timestampEpochMs = nowEpochMs(),
                displacementUm = 0.0,
                confidence = 0.0,
                valid = false,
                message = "Injected invalid Z sample"
            )
        }
        val snapshot = environment.snapshot()
        val physicalGap = abs(snapshot.pose.zUm - snapshot.surfaceZUm)
        val noise = 0.006 * sin(sampleIndex++ * 1.618)
        val displacement = physicalGap - zeroOffsetUm + noise
        val saturated = displacement !in capabilities().minimumDisplacementUm..capabilities().maximumDisplacementUm
        return ZDisplacementSample(
            timestampEpochMs = nowEpochMs(),
            displacementUm = displacement.coerceIn(
                capabilities().minimumDisplacementUm,
                capabilities().maximumDisplacementUm
            ),
            confidence = if (saturated) 0.2 else 0.995,
            valid = !saturated,
            saturated = saturated,
            message = if (saturated) "Synthetic sensor saturated" else null
        )
    }

    private fun ensureConnected() = check(connected) { "Simulation Z sensor is not connected" }
}

class UnavailableCameraAcquisitionPort : CameraAcquisitionPort {
    override val descriptor = unavailableDescriptor("camera", "Inspection Camera")
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    override suspend fun connect() = unavailable("Inspection camera")
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = unavailable("Inspection camera")
    override suspend fun capabilities(): CameraCapabilities = unavailable("Inspection camera")
    override suspend fun configure(config: CameraAcquisitionConfig) = unavailable("Inspection camera")
    override suspend fun capture(): CameraFrame = unavailable("Inspection camera")
    override suspend fun startStreaming() = unavailable("Inspection camera")
    override suspend fun stopStreaming() = Unit
}

class UnavailableZDisplacementSensorPort : ZDisplacementSensorPort {
    override val descriptor = unavailableDescriptor("z-displacement", "Z Displacement Sensor")
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    override suspend fun connect() = unavailable("Z displacement sensor")
    override suspend fun disconnect() = Unit
    override suspend fun identify(): String = unavailable("Z displacement sensor")
    override suspend fun capabilities(): ZDisplacementCapabilities = unavailable("Z displacement sensor")
    override suspend fun zero() = unavailable("Z displacement sensor")
    override suspend fun sample(): ZDisplacementSample = unavailable("Z displacement sensor")
}

private fun unavailableDescriptor(id: String, model: String) = DeviceDescriptor(
    id = "unconfigured-$id",
    vendor = "Unconfigured",
    model = model,
    backendMode = DeviceBackendMode.Real,
    verificationState = DeviceVerificationState.ProtocolImplemented
)

private fun unavailable(name: String): Nothing = error(
    "$name adapter is not configured or hardware-verified"
)

private fun readyStatus(identity: String) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Ready,
    identity = identity,
    detail = "Simulation inspection device is ready"
)

private fun disconnectedStatus(identity: String) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Disconnected,
    identity = identity,
    detail = "Simulation inspection device is disconnected"
)

private fun drawFiberTip(
    pixels: ByteArray,
    width: Int,
    height: Int,
    centerX: Double,
    centerY: Double
) {
    val radius = 6
    for (y in (centerY.roundToInt() - radius)..(centerY.roundToInt() + radius)) {
        for (x in (centerX.roundToInt() - radius)..(centerX.roundToInt() + radius)) {
            if (x !in 0 until width || y !in 0 until height) continue
            val dx = x - centerX
            val dy = y - centerY
            val value = (235.0 * exp(-(dx * dx + dy * dy) / 18.0) + 20.0).roundToInt()
            setMax(pixels, width, x, y, value)
        }
    }
}

private fun drawGrating(
    pixels: ByteArray,
    width: Int,
    height: Int,
    centerX: Double,
    centerY: Double,
    angleDeg: Double
) {
    val angle = angleDeg * PI / 180.0
    val cosine = cos(angle)
    val sine = sin(angle)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val dx = x - centerX
            val dy = y - centerY
            val localX = dx * cosine + dy * sine
            val localY = -dx * sine + dy * cosine
            if (abs(localX) <= 24.0 && abs(localY) <= 12.0) {
                val stripe = ((localX + 24.0) / 4.0).toInt()
                val value = if (stripe % 2 == 0) 175 else 55
                setMax(pixels, width, x, y, value)
            }
        }
    }
}

private fun drawFacet(
    pixels: ByteArray,
    width: Int,
    height: Int,
    centerX: Double,
    centerY: Double,
    angleDeg: Double
) {
    val slope = kotlin.math.tan(angleDeg * PI / 180.0)
    for (x in 0 until width) {
        val yCenter = centerY + slope * (x - centerX)
        for (offset in -1..1) {
            val y = yCenter.roundToInt() + offset
            if (y in 0 until height && abs(x - centerX) <= 45.0) {
                setMax(pixels, width, x, y, 205 - abs(offset) * 35)
            }
        }
    }
}

private fun setMax(pixels: ByteArray, width: Int, x: Int, y: Int, value: Int) {
    val index = y * width + x
    val current = pixels[index].toInt() and 0xff
    if (value > current) pixels[index] = value.coerceIn(0, 255).toByte()
}
