package org.jason.siph

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jason.siph.ui.model.PiPhysicalConnectionLifecycle
import org.jason.siph.ui.model.PiPhysicalReadbackCoordinator
import org.jason.siph.ui.model.PiPhysicalReadbackProjection
import org.jason.siph.ui.model.PiPhysicalTcpConnectionConfig
import org.jason.siph.ui.model.PiPhysicalTcpConnectionFactory
import org.jason.siph.ui.model.PiRuntimeMotionCommands

fun main() = application {
    val scope = rememberCoroutineScope()
    val commands = remember { PiRuntimeMotionCommands(scope) }
    val lifecycle = remember { PiPhysicalConnectionLifecycle(commands) }
    val projection = remember { PiPhysicalReadbackProjection(lifecycle, commands) }
    val readback = remember {
        PiPhysicalReadbackCoordinator { ui -> projection.refreshOrError(ui) }
    }
    val sessionOperations = remember { Mutex() }
    var closing by remember { mutableStateOf(false) }
    var closeError by remember { mutableStateOf<String?>(null) }

    Window(
        onCloseRequest = {
            if (!closing) {
                closing = true
                scope.launch {
                    try {
                        sessionOperations.withLock {
                            readback.invalidate()
                            lifecycle.disconnectIfConnected()
                            readback.close()
                            commands.close()
                        }
                        exitApplication()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        closeError = "PI disconnect failed: " +
                            (failure.message ?: "Unknown error")
                        closing = false
                    }
                }
            }
        },
        title = "SiphStudio"
    ) {
        // Recomposition does not own the transport: explicit close path above does.
        if (closeError != null) Text(closeError!!)
        App(
            physicalReadback = { state -> readback.refresh(state) },
            invalidatePhysicalReadback = { readback.invalidate() },
            physicalConnect = { host, port ->
                sessionOperations.withLock {
                    check(!closing) { "Application is closing" }
                    val controller = PiPhysicalTcpConnectionFactory.create(
                        PiPhysicalTcpConnectionConfig(host, port)
                    )
                    try {
                        lifecycle.connect(controller)
                    } catch (failure: Throwable) {
                        try {
                            controller.close()
                        } catch (closeFailure: Throwable) {
                            failure.addSuppressed(closeFailure)
                        }
                        throw failure
                    }
                    readback.invalidate()
                    projection.refreshOrError(org.jason.siph.ui.model.CouplingToolUiState())
                }
            },
            physicalDisconnect = {
                sessionOperations.withLock {
                    check(!closing) { "Application is closing" }
                    readback.invalidate()
                    lifecycle.disconnect()
                }
            }
        )
    }
}
