package org.jason.siph.ui.coupling

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GpuSurfaceSceneTest {

    @Test
    fun gpuSceneUsesSharedAxesPaletteAndOnePickableLayer() {
        val mesh = testMesh()

        val scene = buildGpuSurfaceScene(mesh)

        assertEquals(3, scene.layers.size)
        assertEquals(listOf(false, false, true), scene.layers.map { it.pickable })
        val surfaceLayer = scene.layers.single { it.pickable }
        assertEquals(mesh.rowCount * mesh.columnCount, surfaceLayer.mesh.vertices.size)
        assertEquals(CouplingSurfaceRenderSpec.xAxisLabel, scene.axes?.xLabel)
        assertEquals(CouplingSurfaceRenderSpec.yAxisLabel, scene.axes?.yLabel)
        assertEquals(CouplingSurfaceRenderSpec.zAxisLabel, scene.axes?.zLabel)
        assertNotNull(scene.axes)
        assertTrue(scene.labels.size >= CouplingSurfaceRenderSpec.axisTickCount * 3)
    }

    @Test
    fun gpuVerticesKeepDataCoordinatesAndUseSpatialAspectInWorldCoordinates() {
        val mesh = testMesh()

        val scene = buildGpuSurfaceScene(mesh)
        val firstVertex = scene.layers.single { it.pickable }.mesh.vertices.first()
        val expectedAspect = 0.8f

        assertClose(-10f, firstVertex.dataPosition.x)
        assertClose(-8f, firstVertex.dataPosition.y)
        assertClose(-30f, firstVertex.dataPosition.z)

        assertClose(-1f, firstVertex.position.x)
        assertClose(-expectedAspect, firstVertex.position.y)
        assertClose(0f, firstVertex.position.z)
        assertEquals(-expectedAspect..expectedAspect, scene.axes?.worldYRange)
    }

    @Test
    fun emptyGpuSceneKeepsRendererAliveWithoutMeshLayer() {
        val scene = buildGpuSurfaceScene(null)

        assertEquals(2, scene.layers.size)
        assertTrue(scene.layers.none { it.pickable })
        assertNotNull(scene.axes)
        assertEquals(CouplingSurfaceRenderSpec.backgroundColor.red, scene.backgroundColor.red)
    }

    private fun testMesh(): SurfaceMesh = SurfaceMesh(
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

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(
            abs(expected - actual) <= 1e-5f,
            "Expected $expected, actual $actual"
        )
    }
}
