package org.jason.siph

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.jason.siph.ui.model.PiPhysicalConnectionLifecycle
import org.jason.siph.ui.model.PiPhysicalReadbackCoordinator
import org.jason.siph.ui.model.PiPhysicalReadbackProjection
import org.jason.siph.ui.model.PiRuntimeMotionCommands

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SiphStudio",
    ) {
        val scope = rememberCoroutineScope()
        val commands = remember { PiRuntimeMotionCommands(scope) }
        val lifecycle = remember { PiPhysicalConnectionLifecycle(commands) }
        val projection = remember { PiPhysicalReadbackProjection(lifecycle, commands) }
        val readback = remember {
            PiPhysicalReadbackCoordinator { ui -> projection.refreshOrError(ui) }
        }
        DisposableEffect(readback) {
            onDispose {
                readback.close()
                // Physical connection is deliberately not opened by default.
                // Once hardware connection UI is added, disposal must first
                // coordinate safe disconnection before closing commands.
                commands.close()
            }
        }
        App(
            physicalReadback = { state -> readback.refresh(state) },
            invalidatePhysicalReadback = { readback.invalidate() }
        )
    }
}
