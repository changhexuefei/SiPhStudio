package org.jason.siph.ui.model

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PiPhysicalActionDispatcherTest {
    @Test
    fun noControllerCannotPretendThatMoveOrStopSucceeded() = runBlocking {
        val commands = PiRuntimeMotionCommands(this)
        val dispatcher = PiPhysicalActionDispatcher(commands)
        assertFailsWith<IllegalStateException> {
            dispatcher.dispatch(CouplingToolAction.MoveSafe)
        }
        assertFailsWith<IllegalStateException> {
            dispatcher.dispatch(CouplingToolAction.StopPositioner)
        }
        commands.close()
        Unit
    }

    @Test
    fun unsupportedPhysicalActionsNeverFallThroughToDemo() = runBlocking {
        val commands = PiRuntimeMotionCommands(this)
        val dispatcher = PiPhysicalActionDispatcher(commands)
        assertFailsWith<IllegalStateException> {
            dispatcher.dispatch(CouplingToolAction.ConnectPositioner)
        }
        assertFailsWith<IllegalStateException> {
            dispatcher.dispatch(CouplingToolAction.DisconnectPositioner)
        }
        assertFailsWith<IllegalStateException> {
            dispatcher.dispatch(CouplingToolAction.StartCoupling)
        }
        commands.close()
        Unit
    }
}
