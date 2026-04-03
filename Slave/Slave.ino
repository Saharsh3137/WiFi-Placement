#include <ESP8266WiFi.h>
#include <espnow.h>
#include <EEPROM.h>

ADC_MODE(ADC_VCC); 

int myID = 1; // [!] CHANGE THIS TO 1, 2, AND 3 FOR YOUR RESPECTIVE NODES!

// [!] VERIFY THIS IS YOUR EXACT ESP32 MASTER MAC ADDRESS
uint8_t masterMAC[] = {0xB0, 0xCB, 0xD8, 0xC6, 0x66, 0x7C}; 

unsigned long startSendTime = 0;
unsigned long lastLatency = 0;
unsigned long globalPacketCount = 0;
int routerChannel = 1; 

bool hasWiFiCreds = false;
char currentSSID[32] = "";
char currentPass[64] = "";

// State flags to move heavy lifting out of the ESP-NOW interrupt
int pendingTask = 0; 
unsigned long lastTelemetrySend = 0;

typedef struct struct_message {
  int id; int rssi; int snr; unsigned long packetId; int voltage; int latency;
} struct_message;
struct_message myData;

typedef struct struct_config {
  char msgType[10]; char ssid[32]; char password[64];
} struct_config;

void OnDataSent(uint8_t *mac_addr, uint8_t sendStatus) {
  unsigned long endTime = micros();
  if (sendStatus == 0) {
    lastLatency = (endTime - startSendTime) / 1000;
    if (lastLatency == 0) lastLatency = 1;
  } else lastLatency = 999; 
}

// THE INTERRUPT: Catch the command, set the flag, exit instantly.
void OnDataRecv(uint8_t * mac, uint8_t *incomingData, uint8_t len) {
  if (len == sizeof(struct_config)) {
    struct_config newConfig;
    memcpy(&newConfig, incomingData, sizeof(newConfig));
    
    if (strcmp(newConfig.msgType, "WIFI_UPDT") == 0) {
      EEPROM.put(0, newConfig); EEPROM.commit();
      delay(1000); ESP.restart(); 
    }
    else if (strcmp(newConfig.msgType, "BECOME_AP") == 0) pendingTask = 1;
    else if (strcmp(newConfig.msgType, "STOP_AP") == 0) pendingTask = 2;
    else if (strcmp(newConfig.msgType, "SCAN_N1") == 0) pendingTask = 801;
    else if (strcmp(newConfig.msgType, "SCAN_N2") == 0) pendingTask = 802;
    else if (strcmp(newConfig.msgType, "SCAN_N3") == 0) pendingTask = 803;
    else if (strcmp(newConfig.msgType, "SCAN_RT") == 0) pendingTask = 800;
  }
}

void setup() {
  delay(2000); 
  Serial.begin(115200);

  // Factory Reset
  pinMode(0, INPUT_PULLUP);
  if (digitalRead(0) == LOW) {
    EEPROM.begin(512);
    struct_config blankConfig;
    strcpy(blankConfig.msgType, "EMPTY"); 
    EEPROM.put(0, blankConfig);
    EEPROM.commit();
    delay(1000); ESP.restart(); 
  }

  EEPROM.begin(512);
  struct_config savedConfig; EEPROM.get(0, savedConfig);
  if (strcmp(savedConfig.msgType, "WIFI_UPDT") == 0) {
    hasWiFiCreds = true;
    strcpy(currentSSID, savedConfig.ssid); strcpy(currentPass, savedConfig.password);
  }

  WiFi.mode(WIFI_STA); WiFi.setSleepMode(WIFI_NONE_SLEEP); WiFi.persistent(false); WiFi.disconnect(true); delay(100);

  if (esp_now_init() != 0) return; 
  esp_now_set_self_role(ESP_NOW_ROLE_COMBO);
  esp_now_register_recv_cb(OnDataRecv);
  esp_now_add_peer(masterMAC, ESP_NOW_ROLE_COMBO, 1, NULL, 0);

  if (hasWiFiCreds) {
    WiFi.begin(currentSSID, currentPass);
    unsigned long startAttempt = millis();
    while (WiFi.status() != WL_CONNECTED) {
      delay(500);
      if (millis() - startAttempt > 40000) { 
        struct_config blankConfig; strcpy(blankConfig.msgType, "EMPTY"); 
        EEPROM.put(0, blankConfig); EEPROM.commit();
        delay(500); ESP.restart(); 
      }
    }
    routerChannel = WiFi.channel(); 
    esp_now_register_send_cb(OnDataSent);
  } else {
    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP("SLAVE_DUMMY", "12345678", 1, 1);
  }
}

void loop() {
  if (hasWiFiCreds) {
    if (lastLatency == 999) {
       esp_now_del_peer(masterMAC); esp_now_add_peer(masterMAC, ESP_NOW_ROLE_COMBO, WiFi.channel(), NULL, 0);
    }

    // --- EXECUTE PENDING CALIBRATION TASKS ---
    if (pendingTask > 0) {
      if (pendingTask == 1) {
        WiFi.mode(WIFI_AP_STA);
        WiFi.softAP(("ScoutNode_" + String(myID)).c_str(), "12345678", routerChannel, 0);
      } 
      else if (pendingTask == 2) {
        WiFi.mode(WIFI_STA);
      } 
      else if (pendingTask >= 800) {
        int foundRssi = -99; 
        
        // 1. ROUTER SCAN (Live Check)
        if (pendingTask == 800) {
            if (WiFi.status() != WL_CONNECTED) {
                WiFi.reconnect();
                delay(400); 
            }
            foundRssi = WiFi.RSSI(); 
            if (foundRssi >= 0) foundRssi = -99; // Garbage filter
        } 
        // 2. PEER SCAN (Deep Scan)
        else {
            String target = "";
            if (pendingTask == 801) target = "ScoutNode_1";
            else if (pendingTask == 802) target = "ScoutNode_2";
            else if (pendingTask == 803) target = "ScoutNode_3";

            int n = WiFi.scanNetworks(false, true); 
            for (int i = 0; i < n; i++) {
              if (WiFi.SSID(i) == target) {
                foundRssi = WiFi.RSSI(i);
                break;
              }
            }
            WiFi.scanDelete(); 
        }

        // Package Data
        myData.id = myID; 
        myData.rssi = foundRssi; 
        myData.snr = foundRssi - (-95);
        myData.packetId = globalPacketCount++;
        int rawVolt = ESP.getVcc(); 
        myData.voltage = (rawVolt > 4000) ? 3300 : rawVolt; 
        myData.latency = pendingTask; 
        
        // THE BURST FIRE PROTOCOL (Shotgun approach to beat interference)
        for (int burst = 0; burst < 5; burst++) {
            esp_now_send(masterMAC, (uint8_t *) &myData, sizeof(myData));
            delay(15); 
        }
      }
      
      pendingTask = 0; 
      lastTelemetrySend = millis(); 
    }

    // --- NORMAL TELEMETRY LOOP ---
    if (millis() - lastTelemetrySend > 1000 && pendingTask == 0) {
      myData.id = myID; myData.rssi = WiFi.RSSI(); myData.snr = myData.rssi - (-95); myData.packetId = globalPacketCount++;
      int rawVolt = ESP.getVcc(); myData.voltage = (rawVolt > 4000) ? 3300 : rawVolt; myData.latency = (int)lastLatency; 
      startSendTime = micros(); 
      esp_now_send(masterMAC, (uint8_t *) &myData, sizeof(myData));
      lastTelemetrySend = millis();
    }
    
  } else {
    delay(100); 
  }
}