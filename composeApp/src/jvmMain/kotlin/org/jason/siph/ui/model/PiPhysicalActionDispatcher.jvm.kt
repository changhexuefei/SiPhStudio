package org.jason.siph.ui.model

/**
 * JVM boundary for UI actions targeting a physical PI controller.
 *
 * The common UI reducer remains demo-only. Physical actions must enter this
 * dispatcher and never fall through to demo state mutations as a substitute
 * for a verified hardware acknowledgement.
 *
 * Connection ownership, jog coordinate conversion and auto-coupling are not
 * implemented here; those actions fail closed until dedicated handlers exist.
 */
class PiPhysicalActionDispatcher(
    private val commands: PiRuntimeMotionCommands
) {
    suspend fun dispatch(action: CouplingToolAction) {
        when (action) {
            CouplingToolAction.MoveSafe -> commands.moveToSafePose()
            CouplingToolAction.StopPositioner -> commands.stop()
            CouplingToolAction.ConnectPositioner,
            CouplingToolAction.DisconnectPositioner,
            CouplingToolAction.ReadPose,
            is CouplingToolAction.JogPositioner,
            CouplingToolAction.StartCoupling -> error(
                "Physical PI action has no verified runtime handler: $action"
            )
            else -> error("Not a physical PI command action: $action")
        }
    }
}
