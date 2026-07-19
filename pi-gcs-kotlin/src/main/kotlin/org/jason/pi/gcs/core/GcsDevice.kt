package org.jason.pi.gcs.core

import org.jason.pi.gcs.hexapod.PiAxis

/**
 * Typed PI GCS device facade.
 *
 * High-level methods build commands, [GcsClient] owns serialized transport
 * access and response framing, and [GcsResponseParser] converts replies to
 * domain values.
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

    suspend fun servoOn(axis: PiAxis) {
        command(GcsCommand.servo(axis, enabled = true))
    }

    suspend fun servoOff(axis: PiAxis) {
        command(GcsCommand.servo(axis, enabled = false))
    }

    suspend fun setServo(
        axes: List<PiAxis>,
        enabled: Boolean
    ) {
        val normalized = axes.normalizedAxes("setServo")
        command(GcsCommand.servo(normalized, enabled))
    }

    suspend fun qServo(axis: PiAxis): Boolean {
        val response = query(GcsCommand.qServo(axis))
        return GcsResponseParser.parseAxisBoolean(response, axis)
    }

    suspend fun qServo(axes: List<PiAxis>): Map<PiAxis, Boolean> {
        val normalized = axes.normalizedAxes("qServo")
        val response = queryAxisResponse(
            command = GcsCommand.qServo(normalized),
            expectedAxes = normalized
        )
        return GcsResponseParser.parseAxisBooleanMap(response, normalized)
    }

    suspend fun servoOnAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        setServo(axes, enabled = true)
    }

    suspend fun servoOffAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        setServo(axes, enabled = false)
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
        if (delta == 0.0) return
        command(GcsCommand.moveRelative(axis, delta))
    }

    suspend fun moveRelative(deltas: Map<PiAxis, Double>) {
        val nonZero = deltas.filterValues { it != 0.0 }
        if (nonZero.isEmpty()) return
        command(GcsCommand.moveRelative(nonZero))
    }

    suspend fun qPOS(axis: PiAxis): Double {
        val response = query(GcsCommand.qPosition(axis))
        return GcsResponseParser.parseAxisDouble(response, axis)
    }

    suspend fun qPOS(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        val normalized = axes.normalizedAxes("qPOS")
        val response = queryAxisResponse(
            command = GcsCommand.qPosition(normalized),
            expectedAxes = normalized
        )
        return GcsResponseParser.parseAxisDoubleMap(response, normalized)
    }

    suspend fun qONT(axis: PiAxis): Boolean {
        val response = query(GcsCommand.qOnTarget(axis))
        return GcsResponseParser.parseAxisBoolean(response, axis)
    }

    suspend fun qONT(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Boolean> {
        val normalized = axes.normalizedAxes("qONT")
        val response = queryAxisResponse(
            command = GcsCommand.qOnTarget(normalized),
            expectedAxes = normalized
        )
        return GcsResponseParser.parseAxisBooleanMap(response, normalized)
    }

    suspend fun qTMN(axis: PiAxis): Double {
        val response = query(GcsCommand.qTravelMin(axis))
        return GcsResponseParser.parseAxisDouble(response, axis)
    }

    suspend fun qTMX(axis: PiAxis): Double {
        val response = query(GcsCommand.qTravelMax(axis))
        return GcsResponseParser.parseAxisDouble(response, axis)
    }

    suspend fun qTMN(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        val normalized = axes.normalizedAxes("qTMN")
        val response = queryAxisResponse(
            command = GcsCommand.qTravelMin(normalized),
            expectedAxes = normalized
        )
        return GcsResponseParser.parseAxisDoubleMap(response, normalized)
    }

    suspend fun qTMX(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ): Map<PiAxis, Double> {
        val normalized = axes.normalizedAxes("qTMX")
        val response = queryAxisResponse(
            command = GcsCommand.qTravelMax(normalized),
            expectedAxes = normalized
        )
        return GcsResponseParser.parseAxisDoubleMap(response, normalized)
    }

    suspend fun reference(axis: PiAxis) {
        command(GcsCommand.reference(axis))
    }

    suspend fun referenceAll(
        axes: List<PiAxis> = PiAxis.HEXAPOD_AXES
    ) {
        val normalized = axes.normalizedAxes("referenceAll")
        command(GcsCommand.reference(normalized))
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
        return checkNotNull(client.execute(command)) {
            "PI GCS query returned no response: ${command.text}"
        }
    }

    private suspend fun queryAxisResponse(
        command: GcsCommand,
        expectedAxes: List<PiAxis>
    ): String {
        check(command.isQuery) {
            "Expected a PI GCS query: ${command.text}"
        }

        return client.query(
            command = command.text,
            maxResponseLines = expectedAxes.size,
            isResponseComplete = { response ->
                GcsResponseParser.containsAllAxes(response, expectedAxes)
            }
        )
    }
}

private fun List<PiAxis>.normalizedAxes(operation: String): List<PiAxis> {
    val normalized = distinct()
    require(normalized.isNotEmpty()) { "$operation axes must not be empty" }
    return normalized
}
