/*
 * SixthSense ESP32 wearable firmware -- four motors through two DRV8833 boards.
 *
 * This sketch uses the standard ESP32 Arduino BLE library (BLEDevice.h), the
 * same library as firmware/diagnostics/ble_radio_check. Do not install NimBLE
 * for this version of the project.
 *
 * BLE contract (must match android/.../bluetooth/BleProtocol.kt):
 *   device name: SixthSense-ESP32
 *   service:     12345678-1234-1234-1234-1234567890ab
 *   command:     12345678-1234-1234-1234-1234567890ac (write one ASCII byte)
 *   status:      12345678-1234-1234-1234-1234567890ad (read + notify ASCII text)
 *   commands: L, F, R, B, A, S
 *
 * Driver wiring -- every motor is attached across one COMPLETE H-bridge:
 *   Left:   DRV8833 #1 AOUT1/AOUT2; AIN1=GPIO25, AIN2=GPIO26
 *   Front:  DRV8833 #1 BOUT1/BOUT2; BIN1=GPIO27, BIN2=GPIO14
 *   Right:  DRV8833 #2 AOUT1/AOUT2; AIN1=GPIO32, AIN2=GPIO33
 *   Back:   DRV8833 #2 BOUT1/BOUT2; BIN1=GPIO18, BIN2=GPIO19
 *   Enable: connect the SLEEP/EN pin of both boards to GPIO13 and add a 10k
 *           pull-down resistor to GND. A low level disables all H-bridges.
 */

#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

namespace {
constexpr char DEVICE_NAME[] = "SixthSense-ESP32";
constexpr char SERVICE_UUID[] = "12345678-1234-1234-1234-1234567890ab";
constexpr char COMMAND_UUID[] = "12345678-1234-1234-1234-1234567890ac";
constexpr char STATUS_UUID[] = "12345678-1234-1234-1234-1234567890ad";
constexpr uint8_t DRIVER_ENABLE_PIN = 13;
constexpr uint32_t PULSE_DURATION_MS = 180;
constexpr uint32_t COMMAND_WATCHDOG_MS = 1'200;

struct MotorPins { uint8_t in1; uint8_t in2; };
constexpr MotorPins LEFT_MOTOR {25, 26};
constexpr MotorPins FRONT_MOTOR {27, 14};
constexpr MotorPins RIGHT_MOTOR {32, 33};
constexpr MotorPins BACK_MOTOR {18, 19};
constexpr MotorPins MOTORS[] = {LEFT_MOTOR, FRONT_MOTOR, RIGHT_MOTOR, BACK_MOTOR};

enum class Command : uint8_t { STOP, LEFT, FRONT, RIGHT, BACK, ALL };

portMUX_TYPE commandMux = portMUX_INITIALIZER_UNLOCKED;
volatile Command activeCommand = Command::STOP;
volatile uint32_t pulseEndsAt = 0;
volatile uint32_t lastCommandAt = 0;
volatile bool connected = false;
BLECharacteristic* statusCharacteristic = nullptr;
BLEAdvertising* advertising = nullptr;

bool timeReached(uint32_t now, uint32_t deadline) {
  return static_cast<int32_t>(now - deadline) >= 0;
}

void writeMotor(const MotorPins& motor, bool on) {
  digitalWrite(motor.in1, on ? HIGH : LOW);
  digitalWrite(motor.in2, LOW);
}

void coastAllMotors() {
  for (const MotorPins& motor : MOTORS) writeMotor(motor, false);
  digitalWrite(DRIVER_ENABLE_PIN, LOW);
}

void driveCommand(Command command) {
  // DRV8833 wake-up needs up to 1 ms before its H-bridges are driven.
  digitalWrite(DRIVER_ENABLE_PIN, HIGH);
  delayMicroseconds(1'200);
  writeMotor(LEFT_MOTOR, command == Command::LEFT || command == Command::ALL);
  writeMotor(FRONT_MOTOR, command == Command::FRONT || command == Command::ALL);
  writeMotor(RIGHT_MOTOR, command == Command::RIGHT || command == Command::ALL);
  writeMotor(BACK_MOTOR, command == Command::BACK || command == Command::ALL);
}

void publishStatus(const char* value) {
  if (statusCharacteristic != nullptr) {
    statusCharacteristic->setValue(value);
    if (connected) statusCharacteristic->notify();
  }
  Serial.printf("[status] %s\n", value);
}

void requestStop(const char* status) {
  portENTER_CRITICAL(&commandMux);
  activeCommand = Command::STOP;
  pulseEndsAt = 0;
  portEXIT_CRITICAL(&commandMux);
  coastAllMotors();
  publishStatus(status);
}

bool decodeCommand(uint8_t byte, Command& result) {
  switch (byte) {
    case 'L': result = Command::LEFT; return true;
    case 'F': result = Command::FRONT; return true;
    case 'R': result = Command::RIGHT; return true;
    case 'B': result = Command::BACK; return true;
    case 'A': result = Command::ALL; return true;
    case 'S': result = Command::STOP; return true;
    default: return false;
  }
}

const char* nameOf(Command command) {
  switch (command) {
    case Command::LEFT: return "PULSE_LEFT";
    case Command::FRONT: return "PULSE_FRONT";
    case Command::RIGHT: return "PULSE_RIGHT";
    case Command::BACK: return "PULSE_BACK";
    case Command::ALL: return "PULSE_ALL";
    case Command::STOP: return "STOPPED";
  }
  return "STOPPED";
}

class CommandCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    // The ESP32 Arduino BLE library returns Arduino String here.
    const String value = characteristic->getValue();
    if (value.length() != 1) {
      publishStatus("INVALID_COMMAND");
      return;
    }

    Command command;
    if (!decodeCommand(static_cast<uint8_t>(value[0]), command)) {
      publishStatus("INVALID_COMMAND");
      return;
    }
    if (command == Command::STOP) {
      requestStop("STOPPED");
      return;
    }

    const uint32_t now = millis();
    portENTER_CRITICAL(&commandMux);
    activeCommand = command;
    lastCommandAt = now;
    pulseEndsAt = now + PULSE_DURATION_MS;
    portEXIT_CRITICAL(&commandMux);
    publishStatus(nameOf(command));
  }
};

class ServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    connected = true;
    requestStop("CONNECTED");
  }

  void onDisconnect(BLEServer* server) override {
    connected = false;
    requestStop("DISCONNECTED");
    if (advertising != nullptr) advertising->start();
  }
};

void updateMotors() {
  Command command;
  uint32_t deadline;
  uint32_t last;
  bool isConnected;
  portENTER_CRITICAL(&commandMux);
  command = activeCommand;
  deadline = pulseEndsAt;
  last = lastCommandAt;
  isConnected = connected;
  portEXIT_CRITICAL(&commandMux);

  if (command == Command::STOP) return;
  const uint32_t now = millis();
  if (!isConnected || timeReached(now, deadline)) {
    requestStop("PULSE_COMPLETE");
    return;
  }
  if (timeReached(now, last + COMMAND_WATCHDOG_MS)) {
    requestStop("WATCHDOG_STOP");
    return;
  }
  driveCommand(command);
}
}  // namespace

void setup() {
  Serial.begin(115200);
  for (const MotorPins& motor : MOTORS) {
    pinMode(motor.in1, OUTPUT);
    pinMode(motor.in2, OUTPUT);
  }
  pinMode(DRIVER_ENABLE_PIN, OUTPUT);
  coastAllMotors();

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
  // Recommended preferred connection interval range for broad phone support.
  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  advertising->start();
  Serial.printf("Advertising as %s\n", DEVICE_NAME);
}

void loop() {
  updateMotors();
  delay(5);
}
