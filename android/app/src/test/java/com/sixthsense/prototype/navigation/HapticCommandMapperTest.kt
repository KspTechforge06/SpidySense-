package com.sixthsense.prototype.navigation

import com.sixthsense.prototype.vision.DirectionZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HapticCommandMapperTest {
    @Test fun `forward camera center zone maps to the front motor`() {
        assertEquals(HapticCommand.LEFT, HapticCommandMapper.forZone(DirectionZone.LEFT))
        assertEquals(HapticCommand.FRONT, HapticCommandMapper.forZone(DirectionZone.CENTER))
        assertEquals(HapticCommand.RIGHT, HapticCommandMapper.forZone(DirectionZone.RIGHT))
    }

    @Test fun `unknown zone emits no motor command`() {
        assertNull(HapticCommandMapper.forZone(DirectionZone.UNKNOWN))
    }
}
