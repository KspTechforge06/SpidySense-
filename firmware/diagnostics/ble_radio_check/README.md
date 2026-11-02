# ESP32 BLE radio check

Use this sketch only to diagnose a board that is not visible to BLE scanners.

1. Disconnect the ESP32 from both DRV8833 boards, all motors, and the motor power supply. Leave it connected only by USB.
2. In Arduino IDE, select **ESP32 Dev Module** for an ESP32-WROOM-32D DevKit (unless the board vendor specifies another exact profile).
3. Upload `ble_radio_check.ino`.
4. Open Serial Monitor at **115200 baud** and press the `EN`/Reset button.
5. Scan with nRF Connect or LightBlue.

The expected result is a device named `SixthSense-Diag` exposing standard service `0x180A`. It is a minimal BLE-only program and intentionally does not use the project UUIDs or motor pins.

- If this sketch is **not** visible and Serial Monitor does not print the two expected lines, the issue is board selection, upload/serial port, boot, USB power/cable, or a defective board—not the SixthSense motor/BLE code.
- If Serial Monitor prints the lines but no second phone can see it, test with another USB cable/power source and another scanner/phone; then the ESP32 BLE radio or board may be faulty.
- If this sketch is visible, the ESP32 radio is healthy. Reconnect no motor wiring yet, flash the main `esp32_sixthsense.ino`, and report the complete Serial output if the main firmware is still invisible.
