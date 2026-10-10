package org.jason.pi.gcs.simulation

import org.jason.pi.gcs.hexapod.PiHexapodPose
import org.jason.pi.gcs.hexapod.PiMotionPhase
import org.jason.pi.gcs.hexapod.PiMotionState

/**
 * Deterministic offline six-axis movement model.
 *
 * This is NOT an equipment driver and must not be used to infer real standstill,
 * limit-switch operation, servo status, or a verified physical Stop.
 * Time advances only through explicit tick() calls by tests or a simulator.
 */
class VirtualPiHexapodState(
    initialPose: PiHexapodPose = PiHexapodPose.ZERO,
    private val travel: VirtualPiTravelBounds = VirtualPiTravelBounds.symmetric(),
    private val linearSpeedUmPerSecond: Double = 1_000.0,
    private val angularSpeedDegPerSecond: Double = 5.0
) {
    init {
        require(linearSpeedUmPerSecond.isFinite() && linearSpeedUmPerSecond > 0)
        require(angularSpeedDegPerSecond.isFinite() && angularSpeedDegPerSecond > 0)
        require(travel.contains(initialPose)) { "Initial pose outside virtual limits" }
    }

    var pose: PiHexapodPose = initialPose
        private set
    var motion: PiMotionState = PiMotionState()
        private set
    private var target: PiHexapodPose? = null

    fun connect() {
        check(motion.phase == PiMotionPhase.Disconnected)
        motion = PiMotionState(PiMotionPhase.Ready)
    }

    fun moveTo(destination: PiHexapodPose) {
        check(motion.canMove) { "Virtual hexapod not Ready" }
        require(travel.contains(destination)) { "Target outside virtual limits" }
        target = destination
        motion = PiMotionState(PiMotionPhase.Moving)
    }

    fun tick(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0) { "Invalid simulated time step" }
        if (motion.phase != PiMotionPhase.Moving) return
        val goal = checkNotNull(target)
        val next = PiHexapodPose(
            approach(pose.xUm, goal.xUm, linearSpeedUmPerSecond * seconds),
            approach(pose.yUm, goal.yUm, linearSpeedUmPerSecond * seconds),
            approach(pose.zUm, goal.zUm, linearSpeedUmPerSecond * seconds),
            approach(pose.uDeg, goal.uDeg, angularSpeedDegPerSecond * seconds),
            approach(pose.vDeg, goal.vDeg, angularSpeedDegPerSecond * seconds),
            approach(pose.wDeg, goal.wDeg, angularSpeedDegPerSecond * seconds)
        )
        pose = next
        if (next == goal) {
            target = null
            motion = PiMotionState(PiMotionPhase.Ready)
        }
    }

    fun stop() {
        check(motion.phase != PiMotionPhase.Disconnected)
        target = null
        motion = PiMotionState(PiMotionPhase.RecoveryRequired, "Virtual Stop; recovery confirmation required")
    }

    fun fault(reason: String) {
        require(reason.isNotBlank())
        check(motion.phase != PiMotionPhase.Disconnected)
        target = null
        motion = PiMotionState(PiMotionPhase.Fault, reason)
    }

    fun acknowledgeRecovery(operatorConfirmedSafe: Boolean) {
        require(operatorConfirmedSafe) { "Operator confirmation required" }
        check(motion.phase == PiMotionPhase.RecoveryRequired) { "No recovery pending" }
        motion = PiMotionState(PiMotionPhase.Ready)
    }

    fun disconnect() {
        check(motion.phase == PiMotionPhase.Ready || motion.phase == PiMotionPhase.RecoveryRequired ||
            motion.phase == PiMotionPhase.Fault) { "Cannot disconnect while virtual motion is active" }
        target = null
        motion = PiMotionState(PiMotionPhase.Disconnected)
    }

    private fun approach(current: Double, desired: Double, limit: Double): Double =
        when {
            desired > current -> minOf(current + limit, desired)
            desired < current -> maxOf(current - limit, desired)
            else -> current
        }
}

/** Independent axis limits, expressed in the application's micrometre/degree units. */
data class VirtualPiTravelBounds(
    val minimum: PiHexapodPose,
    val maximum: PiHexapodPose
) {
    init {
        require(components(minimum).zip(components(maximum)).all { (lo, hi) ->
            lo.isFinite() && hi.isFinite() && lo <= hi
        })
    }

    fun contains(pose: PiHexapodPose): Boolean =
        components(pose).zip(components(minimum).zip(components(maximum))).all { (v, limits) ->
            v.isFinite() && v >= limits.first && v <= limits.second
        }

    companion object {
        fun symmetric(): VirtualPiTravelBounds = VirtualPiTravelBounds(
            PiHexapodPose(-10_000.0, -10_000.0, -10_000.0, -30.0, -30.0, -30.0),
            PiHexapodPose(10_000.0, 10_000.0, 10_000.0, 30.0, 30.0, 30.0)
        )
    }
}
private fun components(p: PiHexapodPose) =
    listOf(p.xUm, p.yUm, p.zUm, p.uDeg, p.vDeg, p.wDeg)
