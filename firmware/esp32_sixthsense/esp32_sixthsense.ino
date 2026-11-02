/*
 * SixthSense ESP32 wearable firmware -- four motors through two DRV8833 boards,
 * with 0.91" 128x32 I2C OLED display and capacitive touch power switch.
 *
 * Pin assignments:
 *   Left Motor:   GPIO 25 (AIN1), GPIO 26 (AIN2) - DRV #1
 *   Front Motor:  GPIO 27 (BIN1), GPIO 14 (BIN2) - DRV #1
 *   Right Motor:  GPIO 32 (AIN1), GPIO 33 (AIN2) - DRV #2
 *   Back Motor:   GPIO 18 (BIN1), GPIO 19 (BIN2) - DRV #2
 *   DRV Enable:   GPIO 13 (connected to EEP/SLEEP of both DRV8833 boards)
 *
 * OLED 0.91" 128x32 Display (I2C):
 *   SDA:          GPIO 21 (Default I2C Data)
 *   SCL:          GPIO 22 (Default I2C Clock)
 *   VCC:          3.3V
 *   GND:          GND
 *   I2C Address:  0x3C
 *
 * Touch Switch (Power ON/OFF toggle):
 *   Signal (IO):  GPIO 4 (TTP223 or digital touch sensor)
 *   VCC:          3.3V
 *   GND:          GND
 */

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

namespace {
constexpr char DEVICE_NAME[] = "SixthSense-ESP32";
constexpr char SERVICE_UUID[] = "12345678-1234-1234-1234-1234567890ab";
constexpr char COMMAND_UUID[] = "12345678-1234-1234-1234-1234567890ac";
constexpr char STATUS_UUID[] = "12345678-1234-1234-1234-1234567890ad";

// Pin configuration
constexpr uint8_t DRIVER_ENABLE_PIN = 13;
constexpr uint8_t TOUCH_PIN = 4;
constexpr uint32_t PULSE_DURATION_MS = 350;
constexpr uint32_t COMMAND_WATCHDOG_MS = 1'500;

// OLED Display parameters
constexpr uint8_t SCREEN_WIDTH = 128;
constexpr uint8_t SCREEN_HEIGHT = 32;
constexpr int8_t OLED_RESET = -1;
constexpr uint8_t OLED_ADDRESS = 0x3C;

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);
bool oledAvailable = false;

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
volatile bool devicePoweredOn = true; // Power state controlled by touch switch

// Touch switch state
bool lastTouchReading = false;
uint32_t lastTouchToggleTime = 0;
constexpr uint32_t TOUCH_DEBOUNCE_MS = 350;

// UI State tracking for OLED updates
String currentBleStatus = "ADVERTISING";
String currentModeText = "READY";
bool displayNeedsUpdate = true;
uint32_t lastDisplayUpdateMs = 0;

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
  if (devicePoweredOn) {
    digitalWrite(DRIVER_ENABLE_PIN, HIGH);
  } else {
    digitalWrite(DRIVER_ENABLE_PIN, LOW); // Put drivers to sleep when powered off
  }
}

void driveCommand(Command command) {
  if (!devicePoweredOn) return; // Ignore motor drive if switched off
  digitalWrite(DRIVER_ENABLE_PIN, HIGH);
  writeMotor(LEFT_MOTOR, command == Command::LEFT || command == Command::ALL);
  writeMotor(FRONT_MOTOR, command == Command::FRONT || command == Command::ALL);
  writeMotor(RIGHT_MOTOR, command == Command::RIGHT || command == Command::ALL);
  writeMotor(BACK_MOTOR, command == Command::BACK || command == Command::ALL);
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

const char* displayLabelOf(Command command) {
  switch (command) {
    case Command::LEFT: return "< LEFT";
    case Command::FRONT: return "^ FRONT";
    case Command::RIGHT: return "RIGHT >";
    case Command::BACK: return "v BACK";
    case Command::ALL: return "[ALL 4]";
    case Command::STOP: return "READY";
  }
  return "READY";
}

void renderDisplay() {
  if (!oledAvailable) return;

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);

  if (!devicePoweredOn) {
    // Device is toggled OFF
    display.setTextSize(1);
    display.setCursor(16, 4);
    display.print("[ SYSTEM OFF ]");
    display.setCursor(4, 20);
    display.print("Touch sensor to ON");
    display.display();
    return;
  }

  // Row 1: BLE Status header
  display.setTextSize(1);
  display.setCursor(0, 2);
  display.print("BLE: ");
  if (connected) {
    display.print("CONNECTED");
  } else {
    display.print("SEARCHING...");
  }

  // Horizontal divider line
  display.drawFastHLine(0, 13, 128, SSD1306_WHITE);

  // Row 2: Mode / Active command
  display.setTextSize(2);
  display.setCursor(0, 16);
  display.print(currentModeText);

  display.display();
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
  currentModeText = "READY";
  displayNeedsUpdate = true;
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

class CommandCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
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

    if (!devicePoweredOn) {
      publishStatus("DISABLED_OFF");
      return;
    }

    if (command == Command::STOP) {
      requestStop("STOPPED");
      return;
    }

    const uint32_t now = millis();
    portENTER_CRITICAL(&commandMux);
    connected = true;
    activeCommand = command;
    lastCommandAt = now;
    pulseEndsAt = now + PULSE_DURATION_MS;
    portEXIT_CRITICAL(&commandMux);

    currentModeText = displayLabelOf(command);
    displayNeedsUpdate = true;
    Serial.printf("[command] %s received (%ums)\n", nameOf(command), PULSE_DURATION_MS);
    publishStatus(nameOf(command));
  }
};

class ServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    connected = true;
    currentBleStatus = "CONNECTED";
    displayNeedsUpdate = true;
    Serial.println("[ble] Central connected");
    requestStop("CONNECTED");
  }

  void onDisconnect(BLEServer* server) override {
    connected = false;
    currentBleStatus = "SEARCHING...";
    displayNeedsUpdate = true;
    Serial.println("[ble] Central disconnected");
    requestStop("DISCONNECTED");
    if (advertising != nullptr) advertising->start();
  }
};

void checkTouchSwitch() {
  bool reading = digitalRead(TOUCH_PIN);
  uint32_t now = millis();

  // Trigger on rising edge (user touched the switch)
  if (reading && !lastTouchReading && (now - lastTouchToggleTime > TOUCH_DEBOUNCE_MS)) {
    lastTouchToggleTime = now;
    devicePoweredOn = !devicePoweredOn;

    Serial.printf("[touch] Power toggled: %s\n", devicePoweredOn ? "ON" : "OFF");

    if (devicePoweredOn) {
      // Waking up: pulse enable and self-test buzz
      digitalWrite(DRIVER_ENABLE_PIN, HIGH);
      currentModeText = "READY";
      displayNeedsUpdate = true;
      // Brief haptic confirmation that device is ON
      writeMotor(LEFT_MOTOR, true);
      writeMotor(FRONT_MOTOR, true);
      delay(120);
      coastAllMotors();
    } else {
      // Shutting down: turn off all motors and sleep drivers
      requestStop("POWER_OFF");
      currentModeText = "OFF";
      displayNeedsUpdate = true;
    }
  }
  lastTouchReading = reading;
}

void updateMotors() {
  Command command;
  uint32_t deadline;
  uint32_t last;
  portENTER_CRITICAL(&commandMux);
  command = activeCommand;
  deadline = pulseEndsAt;
  last = lastCommandAt;
  portEXIT_CRITICAL(&commandMux);

  if (command == Command::STOP) return;
  const uint32_t now = millis();
  if (timeReached(now, deadline)) {
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

  // Configure Motor pins
  for (const MotorPins& motor : MOTORS) {
    pinMode(motor.in1, OUTPUT);
    pinMode(motor.in2, OUTPUT);
  }
  pinMode(DRIVER_ENABLE_PIN, OUTPUT);
  digitalWrite(DRIVER_ENABLE_PIN, HIGH);
  coastAllMotors();

  // Configure Touch sensor pin with internal pull-down
  pinMode(TOUCH_PIN, INPUT_PULLDOWN);

  // Initialize I2C for OLED at 400kHz (Fast Mode)
  Wire.begin(21, 22);
  Wire.setClock(400000);

  if (display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDRESS)) {
    oledAvailable = true;
    display.clearDisplay();
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.setCursor(20, 4);
    display.print("SixthSense");
    display.setCursor(12, 18);
    display.print("Booting Wearable...");
    display.display();
    Serial.println("[oled] SSD1306 128x32 OLED initialized at 0x3C");
  } else {
    Serial.println("[oled] SSD1306 not found at 0x3C (check SDA=21, SCL=22)");
  }

  // Motor self-test on boot
  Serial.println("[self-test] Starting motor hardware self-test...");
  for (int i = 0; i < 4; i++) {
    writeMotor(MOTORS[i], true);
    delay(200);
    writeMotor(MOTORS[i], false);
    delay(80);
  }
  coastAllMotors();
  Serial.println("[self-test] Hardware self-test complete.");

  // BLE Server initialization
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

  renderDisplay();
}

void loop() {
  checkTouchSwitch();
  updateMotors();

  // Refresh OLED when state changes or at least every 200ms
  uint32_t now = millis();
  if (displayNeedsUpdate || (now - lastDisplayUpdateMs > 200)) {
    renderDisplay();
    displayNeedsUpdate = false;
    lastDisplayUpdateMs = now;
  }

  delay(5);
}
