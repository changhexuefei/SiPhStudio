package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis

/**
 * Minimal typed PI GCS device facade.
 *
 * The shape intentionally follows PIPython's GCSDevice/GCSCommands split:
 * high-level methods build a typed command, GcsClient owns transport access and
 * ERR? checking, and responses are parsed through GcsResponseParser.
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

    suspend fun qIDN(): String {
        return query(GcsCommand.qIDN())
    }

    suspend fun qVER(): String {
        return query(GcsCommand.qVER())
    }

    suspend fun qERR(): Int {
        return client.qERR()
    }

    suspend fun qAxes(): List<PiAxis> {
        val response = query(GcsCommand.qAxes())
        return GcsResponseParser.parseAxes(response)
    }

    suspend fun stopAll() {
        command(GcsCommand.stopAll())
    }

    suspend fun servoOn(axis: PiAxis) {
        command(GcsCommand.servo(axis, enabled = true))
    }

    suspend fun servoOff(axis: PiAxis) {
        command(GcsCommand.servo(axis, enabled = false))
    }

    suspend fun qServo(axis: PiAxis): Boolean {
        val response = query(GcsCommand.qServo(axis))
        return GcsResponseParser.parseAxisBoolean(
            response = response,
            expectedAxis = axis
        )
    }

    suspend fun servoOnAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        axes.forEach { axis ->
            servoOn(axis)
        }
    }

    suspend fun servoOffAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        axes.forEach { axis ->
            servoOff(axis)
        }
    }

    suspend fun moveAbsolute(
        axis: PiAxis,
        target: Double
    ) {
        command(GcsCommand.moveAbsolute(axis, target))
    }

    suspend fun moveAbsolute(
        targets: Map<PiAxis, Double>
    ) {
        if (targets.isEmpty()) return
        command(GcsCommand.moveAbsolute(targets))
    }

    suspend fun moveRelative(
        axis: PiAxis,
        delta: Double
    ) {
        command(GcsCommand.moveRelative(axis, delta))
    }

    suspend fun moveRelative(
        deltas: Map<PiAxis, Double>
    ) {
        val nonZero = deltas.filterValues { it != 0.0 }
        if (nonZero.isEmpty()) return
        command(GcsCommand.moveRelative(nonZero))
    }

    suspend fun qPOS(axis: PiAxis): Double {
        val response = query(GcsCommand.qPosition(axis))
        return GcsResponseParser.parseAxisDouble(
            response = response,
            expectedAxis = axis
        )
    }

    suspend fun qPOS(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        require(axes.isNotEmpty()) {
            "qPOS axes must not be empty"
        }

        val response = query(GcsCommand.qPosition(axes))
        return GcsResponseParser.parseAxisDoubleMap(
            response = response,
            expectedAxes = axes
        )
    }

    suspend fun qONT(axis: PiAxis): Boolean {
        val response = query(GcsCommand.qOnTarget(axis))
        return GcsResponseParser.parseAxisBoolean(
            response = response,
            expectedAxis = axis
        )
    }

    suspend fun qONT(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Boolean> {
        require(axes.isNotEmpty()) {
            "qONT axes must not be empty"
        }

        val response = query(GcsCommand.qOnTarget(axes))
        return GcsResponseParser.parseAxisBooleanMap(
            response = response,
            expectedAxes = axes
        )
    }

    suspend fun qTMN(axis: PiAxis): Double {
        val response = query(GcsCommand.qTravelMin(axis))
        return GcsResponseParser.parseAxisDouble(
            response = response,
            expectedAxis = axis
        )
    }

    suspend fun qTMX(axis: PiAxis): Double {
        val response = query(GcsCommand.qTravelMax(axis))
        return GcsResponseParser.parseAxisDouble(
            response = response,
            expectedAxis = axis
        )
    }

    suspend fun qTMN(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        require(axes.isNotEmpty()) {
            "qTMN axes must not be empty"
        }

        val response = query(GcsCommand.qTravelMin(axes))
        return GcsResponseParser.parseAxisDoubleMap(
            response = response,
            expectedAxes = axes
        )
    }

    suspend fun qTMX(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        require(axes.isNotEmpty()) {
            "qTMX axes must not be empty"
        }

        val response = query(GcsCommand.qTravelMax(axes))
        return GcsResponseParser.parseAxisDoubleMap(
            response = response,
            expectedAxes = axes
        )
    }

    suspend fun reference(axis: PiAxis) {
        command(GcsCommand.reference(axis))
    }

    suspend fun referenceAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        axes.forEach { axis ->
            reference(axis)
        }
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
        return client.execute(command) ?: ""
    }
}
