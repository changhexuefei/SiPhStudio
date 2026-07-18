package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis

/**
 * 类型安全的 PI GCS 设备门面。
 *
 * 结构对应 PIPython 的 GCSDevice/GCSCommands 分层：
 * - GcsDevice 暴露设备语义；
 * - GcsCommand 生成协议文本并描述响应结构；
 * - GcsClient 串行化传输、读取完整响应并执行 ERR? 检查。
 */
class GcsDevice(
    private val client: GcsClient
) : AutoCloseable {

    val isOpen: Boolean
        get() = client.isOpen

    suspend fun connect() {
        client.connect()
    }

    suspend fun execute(command: GcsCommand): String? {
        return client.execute(command)
    }

    suspend fun qIDN(): String = query(GcsCommand.qIDN())

    suspend fun qVER(): String = query(GcsCommand.qVER())

    suspend fun qERR(): Int = client.qERR()

    suspend fun qAxes(): List<PiAxis> {
        return GcsResponseParser.parseAxes(query(GcsCommand.qAxes()))
    }

    suspend fun stopAll() {
        command(GcsCommand.stopAll())
    }

    suspend fun setServo(states: Map<PiAxis, Boolean>) {
        if (states.isEmpty()) return
        command(GcsCommand.servo(states))
    }

    suspend fun servoOn(axis: PiAxis) {
        setServo(linkedMapOf(axis to true))
    }

    suspend fun servoOff(axis: PiAxis) {
        setServo(linkedMapOf(axis to false))
    }

    suspend fun qServo(axis: PiAxis): Boolean {
        return GcsResponseParser.parseAxisBoolean(
            response = query(GcsCommand.qServo(axis)),
            expectedAxis = axis
        )
    }

    suspend fun qServo(axes: List<PiAxis>): Map<PiAxis, Boolean> {
        require(axes.isNotEmpty()) { "qServo axes must not be empty" }
        return GcsResponseParser.parseAxisBooleanMap(
            response = query(GcsCommand.qServo(axes)),
            expectedAxes = axes
        )
    }

    suspend fun servoOnAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        require(axes.isNotEmpty()) { "servoOnAll axes must not be empty" }
        setServo(axes.associateWith { true })
    }

    suspend fun servoOffAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        require(axes.isNotEmpty()) { "servoOffAll axes must not be empty" }
        setServo(axes.associateWith { false })
    }

    suspend fun moveAbsolute(
        axis: PiAxis,
        target: Double
    ) {
        command(GcsCommand.moveAbsolute(axis, target))
    }

    suspend fun moveAbsolute(targets: Map<PiAxis, Double>) {
        if (targets.isEmpty()) return
        command(GcsCommand.moveAbsolute(targets))
    }

    suspend fun moveRelative(
        axis: PiAxis,
        delta: Double
    ) {
        command(GcsCommand.moveRelative(axis, delta))
    }

    suspend fun moveRelative(deltas: Map<PiAxis, Double>) {
        val nonZero = deltas.filterValues { it != 0.0 }
        if (nonZero.isEmpty()) return
        command(GcsCommand.moveRelative(nonZero))
    }

    suspend fun qPOS(axis: PiAxis): Double {
        return GcsResponseParser.parseAxisDouble(
            response = query(GcsCommand.qPosition(axis)),
            expectedAxis = axis
        )
    }

    suspend fun qPOS(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        require(axes.isNotEmpty()) { "qPOS axes must not be empty" }
        return GcsResponseParser.parseAxisDoubleMap(
            response = query(GcsCommand.qPosition(axes)),
            expectedAxes = axes
        )
    }

    suspend fun qONT(axis: PiAxis): Boolean {
        return GcsResponseParser.parseAxisBoolean(
            response = query(GcsCommand.qOnTarget(axis)),
            expectedAxis = axis
        )
    }

    suspend fun qONT(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Boolean> {
        require(axes.isNotEmpty()) { "qONT axes must not be empty" }
        return GcsResponseParser.parseAxisBooleanMap(
            response = query(GcsCommand.qOnTarget(axes)),
            expectedAxes = axes
        )
    }

    suspend fun qTMN(axis: PiAxis): Double {
        return GcsResponseParser.parseAxisDouble(
            response = query(GcsCommand.qTravelMin(axis)),
            expectedAxis = axis
        )
    }

    suspend fun qTMX(axis: PiAxis): Double {
        return GcsResponseParser.parseAxisDouble(
            response = query(GcsCommand.qTravelMax(axis)),
            expectedAxis = axis
        )
    }

    suspend fun qTMN(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        require(axes.isNotEmpty()) { "qTMN axes must not be empty" }
        return GcsResponseParser.parseAxisDoubleMap(
            response = query(GcsCommand.qTravelMin(axes)),
            expectedAxes = axes
        )
    }

    suspend fun qTMX(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        require(axes.isNotEmpty()) { "qTMX axes must not be empty" }
        return GcsResponseParser.parseAxisDoubleMap(
            response = query(GcsCommand.qTravelMax(axes)),
            expectedAxes = axes
        )
    }

    suspend fun reference(axis: PiAxis) {
        command(GcsCommand.reference(axis))
    }

    suspend fun referenceAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        require(axes.isNotEmpty()) { "referenceAll axes must not be empty" }
        command(GcsCommand.reference(axes))
    }

    override fun close() {
        client.close()
    }

    private suspend fun command(command: GcsCommand) {
        check(command.isCommand) {
            "Expected a PI GCS command: ${command.text}"
        }
        client.execute(command)
    }

    private suspend fun query(command: GcsCommand): String {
        check(command.isQuery) {
            "Expected a PI GCS query: ${command.text}"
        }
        return requireNotNull(client.execute(command)) {
            "PI GCS query 未返回响应: ${command.text}"
        }
    }
}
