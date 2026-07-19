package org.jason.siph.domain.oo

import org.jason.siph.domain.autonomy.DieIndex
import org.jason.siph.domain.autonomy.OpticalCouplerDefinition
import org.jason.siph.domain.autonomy.PhotonicCouplingGeometry
import org.jason.siph.domain.autonomy.SiPhDieDefinition
import org.jason.siph.domain.autonomy.SiPhSubDieDefinition
import org.jason.siph.domain.autonomy.SiPhWaferDefinition
import org.jason.siph.domain.autonomy.WaferCoordinateTransform
import kotlin.test.Test
import kotlin.test.assertEquals

class WaferTraversalPlannerTest {

    @Test
    fun serpentineAlternatesColumnDirectionAndSkipsDisabledSites() {
        val wafer = waferDefinition()
        val route = WaferTraversalPlanner().buildRoute(
            wafer = wafer,
            strategy = WaferTraversalStrategy.Serpentine
        )

        assertEquals(
            listOf(
                DieIndex(0, 0),
                DieIndex(1, 0),
                DieIndex(1, 1),
                DieIndex(0, 1)
            ),
            route.map { it.die }
        )
        assertEquals(listOf("gc"), route.map { it.couplerId }.distinct())
    }

    @Test
    fun explicitRouteKeepsRequestedOrderAndFiltersUnknownSites() {
        val wafer = waferDefinition()
        val all = WaferTraversalPlanner().buildRoute(wafer, WaferTraversalStrategy.RowMajor)
        val requested = listOf(
            all[2],
            all[0],
            all[2],
            all[1].copy(couplerId = "missing")
        )

        val route = WaferTraversalPlanner().buildRoute(
            wafer = wafer,
            strategy = WaferTraversalStrategy.Explicit,
            explicitSiteOrder = requested
        )

        assertEquals(listOf(all[2], all[0]), route)
    }

    private fun waferDefinition(): SiPhWaferDefinition = SiPhWaferDefinition(
        id = "wafer-route",
        diameterMm = 200.0,
        transform = WaferCoordinateTransform(
            originStageXUm = 0.0,
            originStageYUm = 0.0,
            diePitchXUm = 1_000.0,
            diePitchYUm = 1_000.0
        ),
        dies = listOf(
            die(0, 0),
            die(1, 0),
            die(0, 1),
            die(1, 1),
            die(2, 1, enabled = false)
        ),
        createdAtEpochMs = 1L
    )

    private fun die(column: Int, row: Int, enabled: Boolean = true) = SiPhDieDefinition(
        index = DieIndex(column, row),
        enabled = enabled,
        subDies = listOf(
            SiPhSubDieDefinition(
                id = "sub",
                name = "Sub",
                originOffsetXUm = 0.0,
                originOffsetYUm = 0.0,
                couplers = listOf(
                    OpticalCouplerDefinition(
                        id = "gc",
                        name = "Grating",
                        geometry = PhotonicCouplingGeometry.VerticalGrating,
                        offsetXUm = 0.0,
                        offsetYUm = 0.0
                    )
                )
            )
        )
    )
}
