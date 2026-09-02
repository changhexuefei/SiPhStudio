package org.jason.siph.ui.coupling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AerospaceSurfaceColorScaleTest {

    @Test
    fun minimumAndBelowMinimumUseFirstColorStop() {
        val expected = AerospaceSurfaceColorScale.stops.first().color

        assertEquals(expected, AerospaceSurfaceColorScale.colorAt(0f))
        assertEquals(expected, AerospaceSurfaceColorScale.colorAt(-1f))
    }

    @Test
    fun maximumAndAboveMaximumUseLastColorStop() {
        val expected = AerospaceSurfaceColorScale.stops.last().color

        assertEquals(expected, AerospaceSurfaceColorScale.colorAt(1f))
        assertEquals(expected, AerospaceSurfaceColorScale.colorAt(2f))
    }

    @Test
    fun interiorValueInterpolatesBetweenStops() {
        val color = AerospaceSurfaceColorScale.colorAt(0.5f)

        assertNotEquals(AerospaceSurfaceColorScale.stops.first().color, color)
        assertNotEquals(AerospaceSurfaceColorScale.stops.last().color, color)
    }
}
