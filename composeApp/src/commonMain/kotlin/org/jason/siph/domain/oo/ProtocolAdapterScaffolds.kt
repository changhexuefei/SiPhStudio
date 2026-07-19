package org.jason.siph.domain.oo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jason.siph.domain.autonomy.AutonomyCapabilityState
import org.jason.siph.domain.autonomy.AutonomyCapabilityStatus
import org.jason.siph.domain.autonomy.MeasurementSiteKey
import org.jason.siph.domain.autonomy.SiPhWaferDefinition

/**
 * 厂商命令必须由编程手册或真机通信记录提供；这里不内置未经验证的默认命令。
 */
interface TunableLaserCommandProfile {
    fun identifyQuery(): String
    fun setWavelengthCommand(valueNm: Double): String
    fun setPowerCommand(valueDbm: Double): String
    fun outputCommand(enabled: Boolean): String
    fun configureSweepCommands(config: LaserSweepConfig): List<String>
    fun startSweepCommand(): String
    fun stopSweepCommand(): String
    fun snapshotQuery(): String
    fun parseSnapshot(response: String): TunableLaserSnapshot
}

interface OpticalPowerMeterCommandProfile {
    fun identifyQuery(): String
    fun setWavelengthCommand(valueNm: Double, channel: Int): String
    fun readPowerQuery(channel: Int): String
    fun setRangeCommand(range: OpticalPowerRange, channel: Int): String
    fun setAveragingCommand(count: Int, channel: Int): String
    fun configureTriggerCommands(config: OpticalTriggerConfig): List<String>
    fun zeroCommand(channel: Int): String
    fun acquireLogCommands(request: OpticalLogAcquisitionRequest): List<String>
    fun acquireLogQuery(request: OpticalLogAcquisitionRequest): String
    fun parsePowerDbm(response: String): Double
    fun parseLog(response: String, request: OpticalLogAcquisitionRequest): OpticalLogAcquisitionResult
}

interface WaferProberCommandProfile {
    fun identifyQuery(): String
    fun machineStatusQuery(): String
    fun parseMachineStatus(response: String): ProberMachineStatus
    fun loadMapCommands(wafer: SiPhWaferDefinition): List<String>
    fun snapshotQuery(): String
    fun parseSnapshot(response: String): ProberSiteSnapshot
    fun moveToFirstDieCommand(): String
    fun moveToSiteCommands(site: MeasurementSiteKey): List<String>
    fun contactCommand(): String
    fun separateCommand(): String
    fun stopCommand(): String
}

private fun connectingStatus(model: String) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Connecting,
    identity = model,
    detail = "Connecting protocol adapter"
)

private fun readyProtocolStatus(identity: String) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Ready,
    identity = identity,
    detail = "Protocol adapter connected; hardware verification is still required"
)

private fun errorStatus(model: String, error: Throwable) = AutonomyCapabilityStatus(
    state = AutonomyCapabilityState.Error,
    identity = model,
    detail = "Protocol adapter error",
    errorMessage = error.message ?: error::class.simpleName
)

class ProtocolTunableLaserAdapter(
    override val descriptor: DeviceDescriptor,
    private val transport: TextProtocolTransport,
    private val commands: TunableLaserCommandProfile,
    private val declaredCapabilities: TunableLaserCapabilities
) : TunableLaserPort {

    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    init {
        require(descriptor.backendMode != DeviceBackendMode.Simulation)
        require(descriptor.verificationState != DeviceVerificationState.SimulationOnly)
    }

    override suspend fun connect() {
        mutableStatus.value = connectingStatus(descriptor.model)
        runCatching {
            transport.connect()
            val identity = transport.query(commands.identifyQuery()).trim()
            require(identity.isNotBlank()) { "Laser identity response is blank" }
            mutableStatus.value = readyProtocolStatus(identity)
        }.onFailure { error ->
            mutableStatus.value = errorStatus(descriptor.model, error)
            runCatching { transport.disconnect() }
        }.getOrThrow()
    }

    override suspend fun disconnect() {
        runCatching { transport.disconnect() }
        mutableStatus.value = AutonomyCapabilityStatus(
            state = AutonomyCapabilityState.Disconnected,
            identity = descriptor.model,
            detail = "Protocol adapter disconnected"
        )
    }

    override suspend fun identify(): String = transport.query(commands.identifyQuery()).trim()
    override suspend fun capabilities() = declaredCapabilities
    override suspend fun setWavelengthNm(value: Double) = transport.write(commands.setWavelengthCommand(value))
    override suspend fun setPowerDbm(value: Double) = transport.write(commands.setPowerCommand(value))
    override suspend fun setOutputEnabled(enabled: Boolean) = transport.write(commands.outputCommand(enabled))

    override suspend fun configureSweep(config: LaserSweepConfig) {
        commands.configureSweepCommands(config).forEach { transport.write(it) }
    }

    override suspend fun startSweep() = transport.write(commands.startSweepCommand())
    override suspend fun stopSweep() = runCatching { transport.write(commands.stopSweepCommand()) }.getOrElse { Unit }
    override suspend fun snapshot(): TunableLaserSnapshot = commands.parseSnapshot(
        transport.query(commands.snapshotQuery())
    )
}

class ProtocolOoPowerMeterAdapter(
    override val descriptor: DeviceDescriptor,
    private val transport: TextProtocolTransport,
    private val commands: OpticalPowerMeterCommandProfile,
    private val declaredCapabilities: OpticalPowerMeterCapabilities
) : OoOpticalPowerMeterPort {

    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    init {
        require(descriptor.backendMode != DeviceBackendMode.Simulation)
        require(descriptor.verificationState != DeviceVerificationState.SimulationOnly)
    }

    override suspend fun connect() {
        mutableStatus.value = connectingStatus(descriptor.model)
        runCatching {
            transport.connect()
            val identity = transport.query(commands.identifyQuery()).trim()
            require(identity.isNotBlank()) { "Power meter identity response is blank" }
            mutableStatus.value = readyProtocolStatus(identity)
        }.onFailure { error ->
            mutableStatus.value = errorStatus(descriptor.model, error)
            runCatching { transport.disconnect() }
        }.getOrThrow()
    }

    override suspend fun disconnect() {
        runCatching { transport.disconnect() }
        mutableStatus.value = AutonomyCapabilityStatus(
            state = AutonomyCapabilityState.Disconnected,
            identity = descriptor.model,
            detail = "Protocol adapter disconnected"
        )
    }

    override suspend fun identify(): String = transport.query(commands.identifyQuery()).trim()
    override suspend fun capabilities() = declaredCapabilities

    override suspend fun setWavelengthNm(wavelengthNm: Double, channel: Int) {
        transport.write(commands.setWavelengthCommand(wavelengthNm, channel))
    }

    override suspend fun readPowerDbm(channel: Int): Double = commands.parsePowerDbm(
        transport.query(commands.readPowerQuery(channel))
    ).also { require(it.isFinite()) { "Power meter returned a non-finite value" } }

    override suspend fun setRange(range: OpticalPowerRange, channel: Int) {
        transport.write(commands.setRangeCommand(range, channel))
    }

    override suspend fun setAveraging(count: Int, channel: Int) {
        transport.write(commands.setAveragingCommand(count, channel))
    }

    override suspend fun configureTrigger(config: OpticalTriggerConfig) {
        commands.configureTriggerCommands(config).forEach { transport.write(it) }
    }

    override suspend fun zero(channel: Int) {
        transport.write(commands.zeroCommand(channel))
    }

    override suspend fun acquireLog(request: OpticalLogAcquisitionRequest): OpticalLogAcquisitionResult {
        commands.acquireLogCommands(request).forEach { transport.write(it) }
        return commands.parseLog(
            response = transport.query(commands.acquireLogQuery(request)),
            request = request
        )
    }
}

class ProtocolWaferProberAdapter(
    override val descriptor: DeviceDescriptor,
    private val transport: TextProtocolTransport,
    private val commands: WaferProberCommandProfile,
    private val declaredCapabilities: WaferProberCapabilities
) : WaferProberPort {

    private val mutableStatus = MutableStateFlow(AutonomyCapabilityStatus())
    override val status: StateFlow<AutonomyCapabilityStatus> = mutableStatus.asStateFlow()

    init {
        require(descriptor.backendMode != DeviceBackendMode.Simulation)
        require(descriptor.verificationState != DeviceVerificationState.SimulationOnly)
    }

    override suspend fun connect() {
        mutableStatus.value = connectingStatus(descriptor.model)
        runCatching {
            transport.connect()
            val identity = transport.query(commands.identifyQuery()).trim()
            require(identity.isNotBlank()) { "Prober identity response is blank" }
            mutableStatus.value = readyProtocolStatus(identity)
        }.onFailure { error ->
            mutableStatus.value = errorStatus(descriptor.model, error)
            runCatching { transport.disconnect() }
        }.getOrThrow()
    }

    override suspend fun disconnect() {
        runCatching { transport.disconnect() }
        mutableStatus.value = AutonomyCapabilityStatus(
            state = AutonomyCapabilityState.Disconnected,
            identity = descriptor.model,
            detail = "Protocol adapter disconnected"
        )
    }

    override suspend fun identify(): String = transport.query(commands.identifyQuery()).trim()
    override suspend fun capabilities() = declaredCapabilities
    override suspend fun machineStatus(): ProberMachineStatus = commands.parseMachineStatus(
        transport.query(commands.machineStatusQuery())
    )

    override suspend fun loadMap(wafer: SiPhWaferDefinition) {
        commands.loadMapCommands(wafer).forEach { transport.write(it) }
    }

    override suspend fun snapshot(): ProberSiteSnapshot = commands.parseSnapshot(
        transport.query(commands.snapshotQuery())
    )

    override suspend fun moveToFirstDie(): ProberSiteSnapshot {
        transport.write(commands.moveToFirstDieCommand())
        return snapshot()
    }

    override suspend fun moveToSite(site: MeasurementSiteKey): ProberSiteSnapshot {
        commands.moveToSiteCommands(site).forEach { transport.write(it) }
        return snapshot()
    }

    override suspend fun contact() = transport.write(commands.contactCommand())
    override suspend fun separate() = runCatching { transport.write(commands.separateCommand()) }.getOrElse { Unit }
    override suspend fun stop() = runCatching { transport.write(commands.stopCommand()) }.getOrElse { Unit }
}

fun createUnverifiedSantecTslAdapter(
    model: String,
    transport: TextProtocolTransport,
    commands: TunableLaserCommandProfile,
    capabilities: TunableLaserCapabilities,
    backendMode: DeviceBackendMode = DeviceBackendMode.Real
): TunableLaserPort = ProtocolTunableLaserAdapter(
    descriptor = DeviceDescriptor(
        id = "santec-tsl-${model.lowercase()}",
        vendor = "Santec",
        model = model,
        backendMode = backendMode,
        verificationState = DeviceVerificationState.ProtocolImplemented
    ),
    transport = transport,
    commands = commands,
    declaredCapabilities = capabilities
)

fun createUnverifiedSantecMpmAdapter(
    model: String,
    transport: TextProtocolTransport,
    commands: OpticalPowerMeterCommandProfile,
    capabilities: OpticalPowerMeterCapabilities,
    backendMode: DeviceBackendMode = DeviceBackendMode.Real
): OoOpticalPowerMeterPort = ProtocolOoPowerMeterAdapter(
    descriptor = DeviceDescriptor(
        id = "santec-mpm-${model.lowercase()}",
        vendor = "Santec",
        model = model,
        backendMode = backendMode,
        verificationState = DeviceVerificationState.ProtocolImplemented
    ),
    transport = transport,
    commands = commands,
    declaredCapabilities = capabilities
)

fun createUnverifiedMpiSentioAdapter(
    transport: TextProtocolTransport,
    commands: WaferProberCommandProfile,
    capabilities: WaferProberCapabilities,
    backendMode: DeviceBackendMode = DeviceBackendMode.Real
): WaferProberPort = ProtocolWaferProberAdapter(
    descriptor = DeviceDescriptor(
        id = "mpi-sentio",
        vendor = "MPI",
        model = "Sentio",
        backendMode = backendMode,
        verificationState = DeviceVerificationState.ProtocolImplemented
    ),
    transport = transport,
    commands = commands,
    declaredCapabilities = capabilities
)
