package org.jason.siph.domain.inspection

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

/** Vendor SDK or native wrapper boundary. It must not contain workflow logic. */
interface CameraSdkBackend {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun identify(): String
    suspend fun capabilities(): CameraCapabilities
    suspend fun configure(config: CameraAcquisitionConfig)
    suspend fun capture(): CameraFrame
    suspend fun startStreaming()
    suspend fun stopStreaming()
}

interface ZDisplacementBackend {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun identify(): String
    suspend fun capabilities(): ZDisplacementCapabilities
    suspend fun zero()
    suspend fun sample(): ZDisplacementSample
}

class SdkCameraAcquisitionAdapter(
    override val descriptor: DeviceDescriptor,
    private val backend: CameraSdkBackend
) : CameraAcquisitionPort {
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    init {
        require(descriptor.backendMode != DeviceBackendMode.Simulation)
        require(descriptor.verificationState != DeviceVerificationState.SimulationOnly)
    }

    override suspend fun connect() {
        mutableStatus.value = connecting(descriptor.model)
        runCatching {
            backend.connect()
            val identity = backend.identify().also { require(it.isNotBlank()) }
            mutableStatus.value = ready(identity, descriptor.verificationState)
        }.onFailure { error ->
            mutableStatus.value = failed(descriptor.model, error)
            runCatching { backend.disconnect() }
        }.getOrThrow()
    }

    override suspend fun disconnect() {
        runCatching { backend.disconnect() }
        mutableStatus.value = disconnected(descriptor.model)
    }

    override suspend fun identify(): String = backend.identify()
    override suspend fun capabilities(): CameraCapabilities = backend.capabilities()
    override suspend fun configure(config: CameraAcquisitionConfig) = backend.configure(config)
    override suspend fun capture(): CameraFrame = backend.capture()
    override suspend fun startStreaming() = backend.startStreaming()
    override suspend fun stopStreaming() = runCatching { backend.stopStreaming() }.getOrElse { Unit }
}

class SdkZDisplacementSensorAdapter(
    override val descriptor: DeviceDescriptor,
    private val backend: ZDisplacementBackend
) : ZDisplacementSensorPort {
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    init {
        require(descriptor.backendMode != DeviceBackendMode.Simulation)
        require(descriptor.verificationState != DeviceVerificationState.SimulationOnly)
    }

    override suspend fun connect() {
        mutableStatus.value = connecting(descriptor.model)
        runCatching {
            backend.connect()
            val identity = backend.identify().also { require(it.isNotBlank()) }
            mutableStatus.value = ready(identity, descriptor.verificationState)
        }.onFailure { error ->
            mutableStatus.value = failed(descriptor.model, error)
            runCatching { backend.disconnect() }
        }.getOrThrow()
    }

    override suspend fun disconnect() {
        runCatching { backend.disconnect() }
        mutableStatus.value = disconnected(descriptor.model)
    }

    override suspend fun identify(): String = backend.identify()
    override suspend fun capabilities(): ZDisplacementCapabilities = backend.capabilities()
    override suspend fun zero() = backend.zero()
    override suspend fun sample(): ZDisplacementSample = backend.sample()
}

interface InspectionReplayArchive {
    suspend fun appendFrame(frame: CameraFrame)
    suspend fun appendZSample(sample: ZDisplacementSample)
    suspend fun frames(): List<CameraFrame>
    suspend fun zSamples(): List<ZDisplacementSample>
    suspend fun clear()
}

class InMemoryInspectionReplayArchive : InspectionReplayArchive {
    private val mutex = Mutex()
    private val recordedFrames = mutableListOf<CameraFrame>()
    private val recordedZSamples = mutableListOf<ZDisplacementSample>()

    override suspend fun appendFrame(frame: CameraFrame) {
        mutex.withLock { recordedFrames += frame.copy(pixels = frame.pixels.copyOf()) }
    }

    override suspend fun appendZSample(sample: ZDisplacementSample) {
        mutex.withLock { recordedZSamples += sample }
    }

    override suspend fun frames(): List<CameraFrame> = mutex.withLock {
        recordedFrames.map { it.copy(pixels = it.pixels.copyOf()) }
    }

    override suspend fun zSamples(): List<ZDisplacementSample> = mutex.withLock {
        recordedZSamples.toList()
    }

    override suspend fun clear() {
        mutex.withLock {
            recordedFrames.clear()
            recordedZSamples.clear()
        }
    }
}

class RecordingCameraAcquisitionPort(
    private val delegate: CameraAcquisitionPort,
    private val archive: InspectionReplayArchive
) : CameraAcquisitionPort by delegate {
    override suspend fun capture(): CameraFrame = delegate.capture().also { archive.appendFrame(it) }
}

class RecordingZDisplacementSensorPort(
    private val delegate: ZDisplacementSensorPort,
    private val archive: InspectionReplayArchive
) : ZDisplacementSensorPort by delegate {
    override suspend fun sample(): ZDisplacementSample = delegate.sample().also { archive.appendZSample(it) }
}

class ReplayCameraAcquisitionPort(
    frames: List<CameraFrame>,
    private val loop: Boolean = false
) : CameraAcquisitionPort {
    override val descriptor = DeviceDescriptor(
        id = "replay-inspection-camera",
        vendor = "SiPhStudio",
        model = "Recorded Camera Replay",
        backendMode = DeviceBackendMode.Replay,
        verificationState = DeviceVerificationState.ProtocolImplemented
    )
    private val recorded = frames.map { it.copy(pixels = it.pixels.copyOf()) }
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    private var connected = false
    private var cursor = 0

    init {
        require(recorded.isNotEmpty()) { "Camera replay requires at least one frame" }
    }

    override suspend fun connect() {
        connected = true
        cursor = 0
        mutableStatus.value = ready(identify(), descriptor.verificationState)
    }

    override suspend fun disconnect() {
        connected = false
        mutableStatus.value = disconnected(descriptor.model)
    }

    override suspend fun identify(): String {
        check(connected)
        return "SiPhStudio,Recorded Camera Replay,${recorded.size},1.0"
    }

    override suspend fun capabilities() = CameraCapabilities(
        maximumWidthPx = recorded.maxOf { it.widthPx },
        maximumHeightPx = recorded.maxOf { it.heightPx },
        minimumExposureUs = recorded.minOf { it.exposureUs },
        maximumExposureUs = recorded.maxOf { it.exposureUs },
        supportsHardwareTrigger = false,
        supportsStreaming = true
    )

    override suspend fun configure(config: CameraAcquisitionConfig) {
        check(connected)
    }

    override suspend fun capture(): CameraFrame {
        check(connected)
        if (cursor >= recorded.size) {
            if (loop) cursor = 0 else error("Camera replay exhausted")
        }
        return recorded[cursor++].let { it.copy(pixels = it.pixels.copyOf()) }
    }

    override suspend fun startStreaming() {
        check(connected)
    }

    override suspend fun stopStreaming() = Unit
}

class ReplayZDisplacementSensorPort(
    samples: List<ZDisplacementSample>,
    private val loop: Boolean = false
) : ZDisplacementSensorPort {
    override val descriptor = DeviceDescriptor(
        id = "replay-z-displacement",
        vendor = "SiPhStudio",
        model = "Recorded Z Sensor Replay",
        backendMode = DeviceBackendMode.Replay,
        verificationState = DeviceVerificationState.ProtocolImplemented
    )
    private val recorded = samples.toList()
    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()
    private var connected = false
    private var cursor = 0

    init {
        require(recorded.isNotEmpty()) { "Z replay requires at least one sample" }
    }

    override suspend fun connect() {
        connected = true
        cursor = 0
        mutableStatus.value = ready(identify(), descriptor.verificationState)
    }

    override suspend fun disconnect() {
        connected = false
        mutableStatus.value = disconnected(descriptor.model)
    }

    override suspend fun identify(): String {
        check(connected)
        return "SiPhStudio,Recorded Z Sensor Replay,${recorded.size},1.0"
    }

    override suspend fun capabilities() = ZDisplacementCapabilities(
        minimumDisplacementUm = recorded.minOf { it.displacementUm },
        maximumDisplacementUm = recorded.maxOf { it.displacementUm }.let { max ->
            if (max == recorded.minOf { it.displacementUm }) max + 1e-6 else max
        },
        resolutionUm = 1e-6,
        maximumSampleRateHz = 1_000.0,
        supportsHardwareZero = false
    )

    override suspend fun zero() {
        check(connected)
    }

    override suspend fun sample(): ZDisplacementSample {
        check(connected)
        if (cursor >= recorded.size) {
            if (loop) cursor = 0 else error("Z displacement replay exhausted")
        }
        return recorded[cursor++]
    }
}

fun createUnverifiedCameraAdapter(
    vendor: String,
    model: String,
    backend: CameraSdkBackend
): CameraAcquisitionPort = SdkCameraAcquisitionAdapter(
    descriptor = DeviceDescriptor(
        id = "camera-${vendor.lowercase()}-${model.lowercase()}",
        vendor = vendor,
        model = model,
        backendMode = DeviceBackendMode.Real,
        verificationState = DeviceVerificationState.ProtocolImplemented
    ),
    backend = backend
)

fun createUnverifiedZDisplacementAdapter(
    vendor: String,
    model: String,
    backend: ZDisplacementBackend
): ZDisplacementSensorPort = SdkZDisplacementSensorAdapter(
    descriptor = DeviceDescriptor(
        id = "z-sensor-${vendor.lowercase()}-${model.lowercase()}",
        vendor = vendor,
        model = model,
        backendMode = DeviceBackendMode.Real,
        verificationState = DeviceVerificationState.ProtocolImplemented
    ),
    backend = backend
)

private fun connecting(model: String) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Connecting,
    identity = model,
    detail = "Connecting inspection adapter"
)

private fun ready(identity: String, verification: DeviceVerificationState) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Ready,
    identity = identity,
    detail = when (verification) {
        DeviceVerificationState.HardwareVerified -> "Inspection adapter is hardware-verified"
        DeviceVerificationState.ProtocolImplemented -> "Adapter connected; hardware verification is required"
        DeviceVerificationState.SimulationOnly -> "Simulation inspection adapter is ready"
    }
)

private fun disconnected(model: String) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Disconnected,
    identity = model,
    detail = "Inspection adapter disconnected"
)

private fun failed(model: String, error: Throwable) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Error,
    identity = model,
    detail = "Inspection adapter error",
    errorMessage = error.message ?: error::class.simpleName
)
