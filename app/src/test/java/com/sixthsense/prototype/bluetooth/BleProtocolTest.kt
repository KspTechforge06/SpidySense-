package com.sixthsense.prototype.bluetooth

import com.sixthsense.prototype.navigation.HapticCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BleProtocolTest {
    @Test fun `protocol encodes single ascii command bytes`() {
        assertArrayEquals(byteArrayOf('L'.code.toByte()), BleProtocol.encode(HapticCommand.LEFT))
        assertArrayEquals(byteArrayOf('C'.code.toByte()), BleProtocol.encode(HapticCommand.CENTER))
        assertArrayEquals(byteArrayOf('R'.code.toByte()), BleProtocol.encode(HapticCommand.RIGHT))
        assertArrayEquals(byteArrayOf('A'.code.toByte()), BleProtocol.encode(HapticCommand.ALL))
        assertArrayEquals(byteArrayOf('S'.code.toByte()), BleProtocol.encode(HapticCommand.STOP))
    }
}
