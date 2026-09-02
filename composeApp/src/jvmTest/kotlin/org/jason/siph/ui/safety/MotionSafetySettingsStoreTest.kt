package org.jason.siph.ui.safety

import org.jason.siph.domain.positioner.OpticalPose
import org.jason.siph.domain.runtime.HardwareRuntimeMode
import org.jason.siph.domain.safety.MotionSafetyInterlockException
import org.jason.siph.domain.safety.MotionSafetyPlanner
import org.jason.siph.ui.model.MotionSafetyAction
import org.jason.siph.ui.model.SafetyInterlockStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionSafetySettingsStoreTest {

    @Test
    fun realModeRequiresNamedFixtureConfirmationBeforeUnlocking() {
        val planner = MotionSafetyPlanner(initialConfig = null)
        val store = MotionSafetySettingsStore(
            runtimeMode = HardwareRuntimeMode.Real,
            planner = planner
        )

        assertFalse(store.state.value.interlockReady)
        assertFalse(planner.isConfigured)
        assertFailsWith<MotionSafetyInterlockException> {
            planner.requireValid(OpticalPose.ZERO)
        }

        store.dispatch(MotionSafetyAction.ApplyProfile)
        assertEquals(SafetyInterlockStatus.Invalid, store.state.value.interlockStatus)
        assertFalse(planner.isConfigured)

        store.dispatch(MotionSafetyAction.UpdateProfileName("H-811 fixture A"))
        store.dispatch(MotionSafetyAction.SetFixtureConfirmed(true))
        store.dispatch(MotionSafetyAction.ApplyProfile)

        assertTrue(store.state.value.interlockReady)
        assertTrue(planner.isConfigured)
        planner.requireValid(OpticalPose.ZERO)
    }

    @Test
    fun clearingRealProfileImmediatelyLocksMotionAgain() {
        val planner = MotionSafetyPlanner(initialConfig = null)
        val store = MotionSafetySettingsStore(
            runtimeMode = HardwareRuntimeMode.Real,
            planner = planner
        )

        store.dispatch(MotionSafetyAction.UpdateProfileName("Verified profile"))
        store.dispatch(MotionSafetyAction.SetFixtureConfirmed(true))
        store.dispatch(MotionSafetyAction.ApplyProfile)
        assertTrue(store.state.value.interlockReady)

        store.dispatch(MotionSafetyAction.ClearAppliedProfile)

        assertFalse(store.state.value.interlockReady)
        assertFalse(planner.isConfigured)
        assertFailsWith<MotionSafetyInterlockException> {
            planner.planMove(OpticalPose.ZERO, OpticalPose.ZERO)
        }
    }

    @Test
    fun demoModeStartsReadyButIsExplicitlyMarkedAsDemoPreset() {
        val planner = MotionSafetyPlanner(initialConfig = null)
        val store = MotionSafetySettingsStore(
            runtimeMode = HardwareRuntimeMode.Demo,
            planner = planner
        )

        assertTrue(store.state.value.interlockReady)
        assertTrue(planner.isConfigured)
        assertTrue(store.state.value.message.contains("Demo"))
    }
}
