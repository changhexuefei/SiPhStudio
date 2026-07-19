package org.jason.siph

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import org.jason.siph.di.RealHardwarePorts
import org.jason.siph.di.createSiPhAppModule
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.ui.autonomy.AutonomousWorkflowStore
import org.jason.siph.ui.safety.MotionSafetySettingsStore
import org.jason.siph.ui.siphtools.CouplingToolScreen
import org.jason.siph.ui.state.CouplingToolStore
import org.jason.siph.ui.theme.AerospacePalette
import org.jason.siph.ui.theme.AerospaceTheme
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.dsl.koinConfiguration

@Composable
@androidx.compose.ui.tooling.preview.Preview
fun App(
    runtimeMode: HardwareRuntimeMode = HardwareRuntimeMode.Demo,
    realHardwarePorts: RealHardwarePorts? = null
) {
    AerospaceTheme {
        val scope = rememberCoroutineScope()
        val appModule = remember(scope, runtimeMode, realHardwarePorts) {
            createSiPhAppModule(
                scope = scope,
                runtimeMode = runtimeMode,
                realHardwarePorts = realHardwarePorts
            )
        }

        KoinApplication(
            configuration = koinConfiguration {
                modules(appModule)
            }
        ) {
            val couplingStore = koinInject<CouplingToolStore>()
            val safetyStore = koinInject<MotionSafetySettingsStore>()
            val autonomousStore = koinInject<AutonomousWorkflowStore>()
            val couplingState by couplingStore.state.collectAsState()
            val safetyState by safetyStore.state.collectAsState()
            val autonomousState by autonomousStore.state.collectAsState()

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = AerospacePalette.Void,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                CouplingToolScreen(
                    state = couplingState,
                    safetyState = safetyState,
                    autonomousState = autonomousState,
                    onAction = couplingStore::dispatch,
                    onSafetyAction = safetyStore::dispatch,
                    onAutonomousAction = autonomousStore::dispatch
                )
            }
        }
    }
}
