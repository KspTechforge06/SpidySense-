/*
 * ESP32-WROOM-32D BLE radio check for SixthSense.
 *
 * Flash THIS sketch with the motor-driver wiring disconnected from the ESP32.
 * It deliberately has no motor, DRV8833, or project BLE dependency. Its only
 * purpose is to prove that the board, selected port, USB power, Arduino ESP32
 * core, and BLE radio can advertise.
 *
 * Expected scanner result: SixthSense-Diag
 * Expected Serial Monitor (115200): BLE diagnostic advertising started
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

namespace {
constexpr char DEVICE_NAME[] = "SixthSense-Diag";
// Standard Device Information service. A short UUID keeps the name in the
// small legacy advertising packet so scanners can show it before connecting.
constexpr char SERVICE_UUID[] = "180A";
constexpr char CHARACTERISTIC_UUID[] = "2A29";
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("Starting BLE diagnostic...");

  BLEDevice::init(DEVICE_NAME);
  BLEServer* server = BLEDevice::createServer();
  BLEService* service = server->createService(SERVICE_UUID);
  BLECharacteristic* manufacturer = service->createCharacteristic(
      CHARACTERISTIC_UUID,
      BLECharacteristic::PROPERTY_READ);
  manufacturer->setValue("SixthSense BLE diagnostic");
  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->start();
  Serial.println("BLE diagnostic advertising started: SixthSense-Diag");
}

void loop() {
  delay(1000);
}
