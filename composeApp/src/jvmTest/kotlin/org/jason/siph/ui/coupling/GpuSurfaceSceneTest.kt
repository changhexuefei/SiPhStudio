package org.jason.siph.ui.coupling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GpuSurfaceSceneTest {

    @Test
    fun gpuSceneUsesSharedAxesPaletteAndOnePickableLayer() {
        val mesh = SurfaceMesh(
            points = listOf(
                listOf(
                    SurfacePoint(-1.0, -1.0, 0.0, -30.0),
                    SurfacePoint(1.0, -1.0, 0.4, -20.0)
                ),
                listOf(
                    SurfacePoint(-1.0, 1.0, 0.7, -12.5),
                    SurfacePoint(1.0, 1.0, 1.0, -5.0)
                )
            ),
            minPower = -30.0,
            maxPower = -5.0,
            xMin = -10.0,
            xMax = 10.0,
            yMin = -8.0,
            yMax = 8.0
        )

        val scene = buildGpuSurfaceScene(mesh)

        assertEquals(1, scene.layers.size)
        assertTrue(scene.layers.single().pickable)
        assertEquals(mesh.rowCount * mesh.columnCount, scene.layers.single().mesh.vertices.size)
        assertEquals(CouplingSurfaceRenderSpec.xAxisLabel, scene.axes?.xLabel)
        assertEquals(CouplingSurfaceRenderSpec.yAxisLabel, scene.axes?.yLabel)
        assertEquals(CouplingSurfaceRenderSpec.zAxisLabel, scene.axes?.zLabel)
        assertNotNull(scene.axes)
    }

    @Test
    fun emptyGpuSceneKeepsRendererAliveWithoutMeshLayer() {
        val scene = buildGpuSurfaceScene(null)

        assertTrue(scene.layers.isEmpty())
        assertNotNull(scene.axes)
        assertEquals(CouplingSurfaceRenderSpec.backgroundColor.red, scene.backgroundColor.red)
    }
}
