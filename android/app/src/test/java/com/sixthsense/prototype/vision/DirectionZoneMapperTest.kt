package com.sixthsense.prototype.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionZoneMapperTest {
    @Test fun `left ends before point 33`() {
        assertEquals(DirectionZone.LEFT, DirectionZoneMapper.fromNormalizedCenter(0.329f))
    }
    @Test fun `point 33 through point 67 are center`() {
        assertEquals(DirectionZone.CENTER, DirectionZoneMapper.fromNormalizedCenter(0.33f))
        assertEquals(DirectionZone.CENTER, DirectionZoneMapper.fromNormalizedCenter(0.67f))
    }
    @Test fun `right begins after point 67`() {
        assertEquals(DirectionZone.RIGHT, DirectionZoneMapper.fromNormalizedCenter(0.671f))
    }
}
