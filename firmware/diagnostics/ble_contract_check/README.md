# BLE contract connection diagnostic

This sketch tests whether a phone can connect to the exact BLE service expected by the SixthSense Android app. It has no motor code.

1. Disconnect DRV8833 boards and motors from the ESP32; leave USB power only.
2. Flash `ble_contract_check.ino` using **ESP32 Dev Module**.
3. Open Serial Monitor at 115200 baud.
4. In nRF Connect, scan for `SixthSense-ESP32` and tap **Connect**.
5. Once connected, write the ASCII byte `L` to command characteristic UUID ending in `90ac`.

Expected serial output:

```text
Advertising as SixthSense-ESP32
Central connected
Command received: L
```

- If **Central connected** does not print, copy the exact nRF Connect connection error and the full Serial Monitor output. The failure is below the app/motor layer.
- If it prints, the BLE contract and radio work. Flash `esp32_sixthsense.ino` next while motor wiring remains disconnected. If the main sketch fails only after wiring drivers/motors, investigate power, ground, and a possible ESP32 reset.
