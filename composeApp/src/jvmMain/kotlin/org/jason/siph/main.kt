package org.jason.siph

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jason.siph.domain.runtime.HardwareRuntimeMode

fun main() = application {
    val runtimeMode = HardwareRuntimeMode.parse(
        System.getProperty("siph.hardware.mode")
    )
    val windowState = rememberWindowState(
        width = 1500.dp,
        height = 920.dp
    )

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "SiPh Studio // Mission Control · ${runtimeMode.text.uppercase()}"
    ) {
        App(runtimeMode = runtimeMode)
    }
}
