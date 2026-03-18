#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BluetoothSerial.h>
#include <nvs_flash.h>
#include <Preferences.h> 

BluetoothSerial SerialBT;
#define SCREEN_ADDRESS 0x3C 
Adafruit_SSD1306 display(128, 64, &Wire, -1);

Preferences preferences;
String savedSSID = "";
String savedPass = "";
bool hasWiFiCreds = false;

typedef struct struct_message {
  int id; int rssi; int snr; unsigned long packetId; int voltage; int latency; 
} struct_message;
struct_message incoming;

typedef struct struct_config {
  char msgType[10]; char ssid[32]; char password[64];
} struct_config;
struct_config configMsg;

// --- THE FIX: Updated array size to match the 3 MACs ---
#define NUM_SLAVES 3
uint8_t slaveMACs[NUM_SLAVES][6] = {
  {0x48, 0x3F, 0xDA, 0x05, 0x37, 0x53}, // Node 1
  {0x48, 0x3F, 0xDA, 0x05, 0x43, 0x86}, // Node 2
  {0xBC, 0xDD, 0xC2, 0x7A, 0x13, 0x16}  // Node 3 
};

struct NodeTracker {
  unsigned long lastPacketId;
  int totalPacketsLost;
  unsigned long lastArrivalTime;
  int jitter;
  unsigned long lastRecvTime;      
  int displayLatency;
  int rssi;
  int snr;
  int voltage;
};
NodeTracker nodes[NUM_SLAVES];

int currentChannel = 1;
const unsigned long TIMEOUT_MS = 5000; 

// --- CAROUSEL VARIABLES ---
int currentNodeDisplay = 0;
unsigned long lastDisplaySwitch = 0;
const unsigned long DISPLAY_SWITCH_MS = 3000; // Time each node stays on screen (3 seconds)

void sendToPhone(int idx) {
  if (SerialBT.hasClient()) {
    SerialBT.print(idx + 1); SerialBT.print(","); 
    SerialBT.print(nodes[idx].rssi); SerialBT.print(",");
    SerialBT.print(nodes[idx].snr); SerialBT.print(",");
    SerialBT.print(nodes[idx].voltage/1000.0); SerialBT.print(",");
    float lossPct = 0.0; 
    if (nodes[idx].lastPacketId > 0) lossPct = (float)nodes[idx].totalPacketsLost / nodes[idx].lastPacketId * 100.0;
    SerialBT.print(lossPct, 2); SerialBT.print(",");
    SerialBT.print(nodes[idx].jitter); SerialBT.print(","); 
    SerialBT.println(nodes[idx].displayLatency); 
  }
}

void OnConfigSent(const uint8_t *mac_addr, esp_now_send_status_t status) {}

void OnDataRecv(const uint8_t * mac, const uint8_t *incomingData, int len) {
  unsigned long now = millis();
  
  if(len == sizeof(incoming)) {
    memcpy(&incoming, incomingData, sizeof(incoming));
    
    int idx = incoming.id - 1; 
    
    if (idx >= 0 && idx < NUM_SLAVES) {
      nodes[idx].rssi = incoming.rssi;
      nodes[idx].snr = incoming.snr;
      nodes[idx].voltage = incoming.voltage;

      if (incoming.latency != 999) {
        nodes[idx].displayLatency = incoming.latency;
      }
      
      if (nodes[idx].lastArrivalTime != 0) {
        long diff = now - nodes[idx].lastArrivalTime;
        int rawJitter = abs((long)diff - 1000); 
        if (rawJitter < 500) nodes[idx].jitter = rawJitter;
      }
      nodes[idx].lastArrivalTime = now;
      
      if (incoming.packetId < nodes[idx].lastPacketId || (now - nodes[idx].lastRecvTime > 3000) || nodes[idx].lastPacketId == 0) {
          nodes[idx].lastPacketId = incoming.packetId; 
          nodes[idx].totalPacketsLost = 0; 
      } else {
          if (incoming.packetId > nodes[idx].lastPacketId + 1) {
              nodes[idx].totalPacketsLost += (incoming.packetId - nodes[idx].lastPacketId - 1);
          }
          nodes[idx].lastPacketId = incoming.packetId;
      }
      
      sendToPhone(idx); 
      nodes[idx].lastRecvTime = now; 
    }
  }
}

void drawScanningScreen() {
  display.clearDisplay(); display.setTextColor(WHITE);
  display.setCursor(0, 0); display.print("NET ANALYZER");
  display.setCursor(15, 25); 
  display.println("AWAITING BT SETUP");
  display.display();
}

void drawMainScreen() {
  display.clearDisplay(); display.setTextColor(WHITE);
  
  // Header
  display.setCursor(0, 0); display.print("NET ANALYZER");
  display.setCursor(85, 0); display.print("NODE "); display.print(currentNodeDisplay + 1);
  display.drawLine(0, 9, 128, 9, WHITE);
  
  int idx = currentNodeDisplay;
  
  // Check if this specific node has gone silent
  if (millis() - nodes[idx].lastRecvTime > TIMEOUT_MS || nodes[idx].lastRecvTime == 0) {
    display.setCursor(25, 25);
    display.print("-- OFFLINE --");
  } else {
    // Row 1
    display.setCursor(0, 16); display.print("RSSI:"); display.print(nodes[idx].rssi); display.print("dBm");
    display.setCursor(74, 16); display.print("SNR:"); display.print(nodes[idx].snr);
    
    // Row 2
    display.setCursor(0, 28); display.print("PING:"); display.print(nodes[idx].displayLatency); display.print("ms");
    float lossPct = 0.0; 
    if (nodes[idx].lastPacketId > 0) lossPct = (float)nodes[idx].totalPacketsLost / nodes[idx].lastPacketId * 100.0;
    display.setCursor(74, 28); display.print("LOSS:"); display.print(lossPct, 1); display.print("%");
    
    // Row 3
    display.setCursor(0, 40); display.print("BATT:"); display.print(nodes[idx].voltage / 1000.0, 2); display.print("V");
  }

  // Footer
  display.drawLine(0, 54, 128, 54, WHITE);
  display.setCursor(0, 56); display.print("CH:"); display.print(currentChannel);
  display.setCursor(80, 56); display.print(SerialBT.hasClient() ? "[BT:ON]" : "[BT:--]");
  display.display();
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  nvs_flash_init();
  preferences.begin("wifi_creds", false);
  savedSSID = preferences.getString("ssid", "");
  savedPass = preferences.getString("pass", "");
  if(savedSSID != "") hasWiFiCreds = true;
  preferences.end();

  SerialBT.begin("ESP32_Scout_Master"); 
  display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS);
  display.clearDisplay();
  
  WiFi.mode(WIFI_STA); 
  WiFi.disconnect(); 

  esp_now_init();
  esp_now_register_recv_cb(esp_now_recv_cb_t(OnDataRecv));
  esp_now_register_send_cb(OnConfigSent);

  for(int i = 0; i < NUM_SLAVES; i++) {
    esp_now_peer_info_t peerInfo = {};
    memcpy(peerInfo.peer_addr, slaveMACs[i], 6);
    peerInfo.channel = 0; 
    esp_now_add_peer(&peerInfo);
  }
}

void loop() {
  unsigned long now = millis();

  // --- CAROUSEL TIMING ---
  if (now - lastDisplaySwitch > DISPLAY_SWITCH_MS) {
    currentNodeDisplay++;
    if (currentNodeDisplay >= NUM_SLAVES) {
      currentNodeDisplay = 0;
    }
    lastDisplaySwitch = now;
  }

  if (SerialBT.available()) {
    String btData = SerialBT.readStringUntil('\n'); btData.trim(); 
    
    if (btData == "RESET") {
      for(int i = 0; i < NUM_SLAVES; i++) {
        nodes[i].totalPacketsLost = 0;
        nodes[i].lastPacketId = 0;
        nodes[i].jitter = 0;
      }
      display.clearDisplay(); display.setCursor(0, 20); display.println("STATS RESET!"); display.display();
      delay(500);
    }
    
    else if (btData.startsWith("WIFI:")) {
      btData.remove(0, 5); 
      int comma = btData.indexOf(','); 
      if (comma > 0) {
        String nSSID = btData.substring(0, comma);
        String nPass = btData.substring(comma + 1);
        
        preferences.begin("wifi_creds", false);
        preferences.putString("ssid", nSSID);
        preferences.putString("pass", nPass);
        preferences.end();

        strcpy(configMsg.msgType, "WIFI_UPDT");
        strcpy(configMsg.ssid, nSSID.c_str());
        strcpy(configMsg.password, nPass.c_str());

        display.clearDisplay(); display.setCursor(0, 20); display.println("UPDATING FLEET..."); display.display();
        WiFi.disconnect();
        delay(100);

        for (int ch = 1; ch <= 11; ch++) {
          esp_wifi_set_promiscuous(true);
          esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE);
          esp_wifi_set_promiscuous(false);

          for(int s = 0; s < NUM_SLAVES; s++) {
            esp_now_del_peer(slaveMACs[s]);
            esp_now_peer_info_t p = {};
            memcpy(p.peer_addr, slaveMACs[s], 6);
            p.channel = ch; 
            esp_now_add_peer(&p);
          }

          for (int i = 0; i < 20; i++) {
            for(int s = 0; s < NUM_SLAVES; s++) {
               esp_now_send(slaveMACs[s], (uint8_t *) &configMsg, sizeof(configMsg));
               delay(5); 
            }
            delay(10);
          }
        }
        
        display.clearDisplay(); display.setCursor(0, 20); display.println("REBOOTING..."); display.display();
        delay(1000);
        ESP.restart(); 
      }
    }
  }

  // --- MULTI-NODE RADAR TRACKER ---
  bool anyNodeConnected = false;
  for(int i = 0; i < NUM_SLAVES; i++) {
    if (now - nodes[i].lastRecvTime <= TIMEOUT_MS && nodes[i].lastRecvTime != 0) {
      anyNodeConnected = true;
    }
  }

  if (!anyNodeConnected && hasWiFiCreds) {
    static unsigned long lastHop = 0;
    if (now - lastHop > 200) { 
      currentChannel++;
      if (currentChannel > 13) currentChannel = 1;
      
      esp_wifi_set_promiscuous(true);
      esp_wifi_set_channel(currentChannel, WIFI_SECOND_CHAN_NONE);
      esp_wifi_set_promiscuous(false);
      lastHop = now;
    }
  }

  // UI Updates
  if (hasWiFiCreds) { 
    static unsigned long lastDraw = 0;
    if (now - lastDraw > 500) { drawMainScreen(); lastDraw = now; }
  } else {
    drawScanningScreen(); 
  }
  delay(10);
}