package com.sixthsense.prototype.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticCooldownTest {
    @Test fun `allows first command then limits repeats until cooldown`() {
        val cooldown = HapticCooldown(700)
        assertTrue(cooldown.canSend(1_000))
        cooldown.markSent(1_000)
        assertFalse(cooldown.canSend(1_699))
        assertTrue(cooldown.canSend(1_700))
    }
}
