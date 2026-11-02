/*
 * SixthSense BLE contract connection diagnostic.
 *
 * This uses the exact device name, service UUID, command UUID, and status UUID
 * used by the Android app, but deliberately does not touch any motor GPIO.
 * Flash it with DRV8833 boards/motors disconnected from the ESP32.
 *
 * Expected Serial Monitor (115200):
 *   Advertising as SixthSense-ESP32
 *   Central connected
 *   Command received: L
 */

#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

namespace {
constexpr char DEVICE_NAME[] = "SixthSense-ESP32";
constexpr char SERVICE_UUID[] = "12345678-1234-1234-1234-1234567890ab";
constexpr char COMMAND_UUID[] = "12345678-1234-1234-1234-1234567890ac";
constexpr char STATUS_UUID[] = "12345678-1234-1234-1234-1234567890ad";

BLEAdvertising* advertising = nullptr;
BLECharacteristic* statusCharacteristic = nullptr;

void publish(const char* value) {
  statusCharacteristic->setValue(value);
  statusCharacteristic->notify();
  Serial.printf("Status: %s\n", value);
}

class ServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    Serial.println("Central connected");
    publish("CONNECTED");
  }

  void onDisconnect(BLEServer* server) override {
    Serial.println("Central disconnected; advertising again");
    if (advertising != nullptr) advertising->start();
  }
};

class CommandCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    const String value = characteristic->getValue();
    Serial.printf("Command received: %s\n", value.c_str());
    publish(value.length() == 1 ? "COMMAND_OK" : "INVALID_COMMAND");
  }
};
}  // namespace

void setup() {
  Serial.begin(115200);
  BLEDevice::init(DEVICE_NAME);
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService* service = server->createService(SERVICE_UUID);

  BLECharacteristic* command = service->createCharacteristic(
      COMMAND_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  command->setCallbacks(new CommandCallbacks());

  statusCharacteristic = service->createCharacteristic(
      STATUS_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  statusCharacteristic->addDescriptor(new BLE2902());
  statusCharacteristic->setValue("ADVERTISING");
  service->start();

  advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  advertising->start();
  Serial.printf("Advertising as %s\n", DEVICE_NAME);
}

void loop() {
  delay(1000);
}
