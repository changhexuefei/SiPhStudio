package org.jason.siph

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.jason.siph.domain.runtime.HardwareRuntimeMode

fun main() = application {
    val runtimeMode = HardwareRuntimeMode.parse(
        System.getProperty("siph.hardware.mode")
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "SiPh Studio · ${runtimeMode.text}",
    ) {
        App(runtimeMode = runtimeMode)
    }
}
