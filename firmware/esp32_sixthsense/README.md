# SixthSense ESP32 firmware

This sketch drives four vibration motors through two DRV8833 breakout boards and exposes the BLE contract used by the Android app.

## Requirements

- ESP32 DevKit with the ESP32 Arduino core 3.x installed.
- The BLE library included with the ESP32 Arduino core. No separate NimBLE library is required.
- Two DRV8833 breakout boards, four rated vibration motors, and a motor supply matched to the motors.

## Connections

```text
GPIO 25 -> driver 1 AIN1       GPIO 26 -> driver 1 AIN2       left motor -> driver 1 AOUT1/AOUT2
GPIO 27 -> driver 1 BIN1       GPIO 14 -> driver 1 BIN2       front motor -> driver 1 BOUT1/BOUT2
GPIO 32 -> driver 2 AIN1       GPIO 33 -> driver 2 AIN2       right motor -> driver 2 AOUT1/AOUT2
GPIO 18 -> driver 2 BIN1       GPIO 19 -> driver 2 BIN2       back motor -> driver 2 BOUT1/BOUT2
GPIO 13 -> SLEEP/EN on both boards, with a 10k resistor to GND
```

Connect the motor-supply negative terminal, both driver GND pins, and ESP32 GND together. Connect motor-supply positive only to the driver motor-supply pins. Do not use an ESP32 GPIO or 3.3V pin as a motor power source.

Breakout labels differ, so verify the board documentation before energizing it. The wiring above applies to breakouts that expose the standard DRV8833 `AIN1/AIN2/BIN1/BIN2` interface.

## Safety behavior

- Every accepted `L`, `F`, `R`, `B`, or `A` command becomes one 180 ms pulse.
- `S` turns all channels off immediately.
- A BLE disconnect stops and disables the driver immediately.
- The driver begins disabled at reset and stays disabled between pulses.
- A stale active command is stopped by the 1.2 second watchdog.

Start with one motor on a supervised bench. Test `L`, `F`, `R`, `B`, `A`, and `S` using nRF Connect or the Android Motor Test screen before wearing the device.

## If the ESP32 is not visible

This firmware is a custom **BLE GATT peripheral**, not a pairing-oriented headset or keyboard. It may not appear in the normal Bluetooth Settings page on a phone or PC. Scan with **nRF Connect** or LightBlue instead; the device should be named `SixthSense-ESP32` and advertise the service UUID in the project README.

After uploading, open Serial Monitor at 115200 baud and press the board's reset button. A healthy boot ends with:

```text
Advertising as SixthSense-ESP32
```

If that line is absent, verify the selected ESP32 board and port, upload the BLE radio diagnostic, and then re-upload this sketch. Disconnect the phone from any old connection or clear the scanner's cached device list before trying again.

For an ESP32-WROOM-32D/DevKit that remains invisible, use the isolated [BLE radio diagnostic](../diagnostics/ble_radio_check/). It uses only the standard ESP32 BLE library and no motor pins, so it separates a radio/board/upload problem from the wearable firmware.
