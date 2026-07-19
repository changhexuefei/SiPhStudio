package org.jason.siph

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import org.jason.siph.di.RealHardwarePorts
import org.jason.siph.di.createPlatformAuditHasher
import org.jason.siph.di.createPlatformProductionRepository
import org.jason.siph.di.createProductionModule
import org.jason.siph.di.createSiPhAppModule
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.ui.autonomy.AutonomousWorkflowStore
import org.jason.siph.ui.inspection.InspectionCalibrationPanel
import org.jason.siph.ui.inspection.InspectionCalibrationStore
import org.jason.siph.ui.model.CouplingToolPage
import org.jason.siph.ui.oo.OoMeasurementPanel
import org.jason.siph.ui.oo.OoMeasurementStore
import org.jason.siph.ui.production.ProductionControlPanel
import org.jason.siph.ui.production.ProductionControlStore
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
        val productionRepository = remember { createPlatformProductionRepository() }
        val productionAuditHasher = remember { createPlatformAuditHasher() }
        val productionModule = remember(
            scope,
            runtimeMode,
            productionRepository,
            productionAuditHasher
        ) {
            createProductionModule(
                scope = scope,
                runtimeMode = runtimeMode,
                repository = productionRepository,
                auditHasher = productionAuditHasher
            )
        }

        KoinApplication(
            configuration = koinConfiguration {
                modules(appModule, productionModule)
            }
        ) {
            val couplingStore = koinInject<CouplingToolStore>()
            val safetyStore = koinInject<MotionSafetySettingsStore>()
            val autonomousStore = koinInject<AutonomousWorkflowStore>()
            val ooStore = koinInject<OoMeasurementStore>()
            val inspectionStore = koinInject<InspectionCalibrationStore>()
            val productionStore = koinInject<ProductionControlStore>()
            val couplingState by couplingStore.state.collectAsState()
            val safetyState by safetyStore.state.collectAsState()
            val autonomousState by autonomousStore.state.collectAsState()
            val ooState by ooStore.state.collectAsState()
            val inspectionState by inspectionStore.state.collectAsState()
            val productionState by productionStore.state.collectAsState()

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = AerospacePalette.Void,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (couplingState.selectedPage == CouplingToolPage.AutonomousAssistant) {
                        ProductionControlPanel(
                            state = productionState,
                            onAction = productionStore::dispatch,
                            modifier = Modifier.fillMaxWidth()
                        )
                        InspectionCalibrationPanel(
                            state = inspectionState,
                            onAction = inspectionStore::dispatch,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OoMeasurementPanel(
                            state = ooState,
                            onAction = ooStore::dispatch,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    CouplingToolScreen(
                        state = couplingState,
                        safetyState = safetyState,
                        autonomousState = autonomousState,
                        onAction = couplingStore::dispatch,
                        onSafetyAction = safetyStore::dispatch,
                        onAutonomousAction = autonomousStore::dispatch,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
