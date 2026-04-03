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

#define NUM_SLAVES 3
// [!] MAKE SURE INDEX 0 IS NODE 1, INDEX 1 IS NODE 2, INDEX 2 IS NODE 3
uint8_t slaveMACs[NUM_SLAVES][6] = {
  {0x48, 0x3F, 0xDA, 0x05, 0x37, 0x53}, // Node 1 
  {0x48, 0x3F, 0xDA, 0x05, 0x43, 0x86}, // Node 2
  {0xBC, 0xDD, 0xC2, 0x7A, 0x13, 0x16}  // Node 3 
};

struct NodeTracker {
  unsigned long lastPacketId; int totalPacketsLost; unsigned long lastArrivalTime;
  int jitter; unsigned long lastRecvTime; int displayLatency; int rssi; int snr; int voltage;
};
NodeTracker nodes[NUM_SLAVES];

int currentChannel = 1;
const unsigned long TIMEOUT_MS = 5000; 
int currentNodeDisplay = 0;
unsigned long lastDisplaySwitch = 0;
const unsigned long DISPLAY_SWITCH_MS = 3000; 

bool isCalibrating = false;
int meshStep = 0; 
unsigned long stepTimer = 0;
unsigned long calibStartTime = 0;

const float OFFSETS[3] = {4.5, 3.5, 0.0}; 
float meshRssi[3][4]; 

float getAvg(float a, float b) {
  if (a == -99.0 && b != -99.0) return b;
  if (b == -99.0 && a != -99.0) return a;
  if (a == -99.0 && b == -99.0) return -99.0;
  return (a + b) / 2.0;
}

void sendToPhone(int idx) {
  if (SerialBT.hasClient() && !isCalibrating) { 
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
      if (isCalibrating && incoming.latency >= 800 && incoming.latency <= 803) {
        int targetIdx = (incoming.latency == 800) ? 3 : (incoming.latency - 801);
        meshRssi[idx][targetIdx] = incoming.rssi + OFFSETS[idx]; 
        return; 
      }

      nodes[idx].rssi = incoming.rssi; nodes[idx].snr = incoming.snr; nodes[idx].voltage = incoming.voltage;
      if (incoming.latency != 999) nodes[idx].displayLatency = incoming.latency;
      
      if (nodes[idx].lastArrivalTime != 0) {
        long diff = now - nodes[idx].lastArrivalTime;
        int rawJitter = abs((long)diff - 1000); 
        if (rawJitter < 500) nodes[idx].jitter = rawJitter;
      }
      nodes[idx].lastArrivalTime = now;
      
      if (incoming.packetId < nodes[idx].lastPacketId || (now - nodes[idx].lastRecvTime > 3000) || nodes[idx].lastPacketId == 0) {
          nodes[idx].lastPacketId = incoming.packetId; nodes[idx].totalPacketsLost = 0; 
      } else {
          if (incoming.packetId > nodes[idx].lastPacketId + 1) nodes[idx].totalPacketsLost += (incoming.packetId - nodes[idx].lastPacketId - 1);
          nodes[idx].lastPacketId = incoming.packetId;
      }
      
      sendToPhone(idx); 
      nodes[idx].lastRecvTime = now; 
    }
  }
}

// --- THE BUGFIX: MASTER COMMAND BURST FIRE ---
// This guarantees the Slaves hear the command even at 7+ meters away
void fireCommand(int nodeIndex, const char* cmd) {
  strcpy(configMsg.msgType, cmd);
  for(int burst = 0; burst < 3; burst++) {
      esp_now_send(slaveMACs[nodeIndex], (uint8_t *)&configMsg, sizeof(configMsg));
      delay(10);
  }
}

void fireAll(const char* cmd) {
  strcpy(configMsg.msgType, cmd);
  for(int burst = 0; burst < 3; burst++) {
      for(int i=0; i<NUM_SLAVES; i++) esp_now_send(slaveMACs[i], (uint8_t *)&configMsg, sizeof(configMsg));
      delay(10);
  }
}

void drawScanningScreen() {
  display.clearDisplay(); display.setTextColor(WHITE);
  display.setCursor(0, 0); display.print("NET ANALYZER");
  display.setCursor(15, 25); display.println("AWAITING BT SETUP");
  display.display();
}

void drawMainScreen() {
  if (isCalibrating) {
    display.clearDisplay(); display.setTextColor(WHITE);
    display.setCursor(0, 5); display.setTextSize(1); display.println("== DYNAMIC MESH ==");
    
    display.setCursor(0, 25); 
    if (meshStep <= 3) display.println("Mapping Node 1...");
    else if (meshStep <= 5) display.println("Mapping Node 2...");
    else if (meshStep <= 7) display.println("Mapping Node 3...");
    else display.println("Triangulating Router...");
    
    display.setCursor(0, 45); 
    int timeLeft = 18 - ((millis() - calibStartTime) / 1000);
    if (timeLeft < 0) timeLeft = 0;
    display.print("Time: "); display.print(timeLeft); display.print("s");
    display.display();
    return;
  }

  display.clearDisplay(); display.setTextColor(WHITE);
  display.setCursor(0, 0); display.print("NET ANALYZER");
  display.setCursor(85, 0); display.print("NODE "); display.print(currentNodeDisplay + 1);
  display.drawLine(0, 9, 128, 9, WHITE);
  
  int idx = currentNodeDisplay;
  if (millis() - nodes[idx].lastRecvTime > TIMEOUT_MS || nodes[idx].lastRecvTime == 0) {
    display.setCursor(25, 25); display.print("-- OFFLINE --");
  } else {
    display.setCursor(0, 16); display.print("RSSI:"); display.print(nodes[idx].rssi); display.print("dBm");
    display.setCursor(74, 16); display.print("SNR:"); display.print(nodes[idx].snr);
    display.setCursor(0, 28); display.print("PING:"); display.print(nodes[idx].displayLatency); display.print("ms");
    float lossPct = 0.0; 
    if (nodes[idx].lastPacketId > 0) lossPct = (float)nodes[idx].totalPacketsLost / nodes[idx].lastPacketId * 100.0;
    display.setCursor(74, 28); display.print("LOSS:"); display.print(lossPct, 1); display.print("%");
    display.setCursor(0, 40); display.print("BATT:"); display.print(nodes[idx].voltage / 1000.0, 2); display.print("V");
  }
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
  
  WiFi.mode(WIFI_STA); WiFi.disconnect(); 

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

  if (now - lastDisplaySwitch > DISPLAY_SWITCH_MS) {
    currentNodeDisplay++;
    if (currentNodeDisplay >= NUM_SLAVES) currentNodeDisplay = 0;
    lastDisplaySwitch = now;
  }

  // =========================================================
  // THE BURST-FIRE ORCHESTRATOR
  // =========================================================
  if (isCalibrating) {
    unsigned long elapsed = now - stepTimer;
    
    // Phase 1: Node 1 becomes a Router
    if (meshStep == 1) {
        fireCommand(0, "BECOME_AP");
        stepTimer = now; meshStep = 2;
    }
    // Phase 1.5: Wait 1.5s for AP to boot, then Others scan Node 1
    else if (meshStep == 2 && elapsed > 1500) {
        fireCommand(1, "SCAN_N1");
        fireCommand(2, "SCAN_N1");
        stepTimer = now; meshStep = 3;
    }
    // Phase 2: Wait 3.5s for deep scan, then Node 2 becomes Router
    else if (meshStep == 3 && elapsed > 3500) {
        fireCommand(0, "STOP_AP");
        fireCommand(1, "BECOME_AP");
        stepTimer = now; meshStep = 4;
    }
    // Phase 2.5: Wait 1.5s, then Others scan Node 2
    else if (meshStep == 4 && elapsed > 1500) {
        fireCommand(0, "SCAN_N2");
        fireCommand(2, "SCAN_N2");
        stepTimer = now; meshStep = 5;
    }
    // Phase 3: Wait 3.5s, then Node 3 becomes Router
    else if (meshStep == 5 && elapsed > 3500) {
        fireCommand(1, "STOP_AP");
        fireCommand(2, "BECOME_AP");
        stepTimer = now; meshStep = 6;
    }
    // Phase 3.5: Wait 1.5s, then Others scan Node 3
    else if (meshStep == 6 && elapsed > 1500) {
        fireCommand(0, "SCAN_N3");
        fireCommand(1, "SCAN_N3");
        stepTimer = now; meshStep = 7;
    }
    // Phase 4: Wait 3.5s, Everyone scans the Real Router
    else if (meshStep == 7 && elapsed > 3500) {
        fireCommand(2, "STOP_AP");
        delay(50); // Let the radio rest before issuing the next scan
        fireAll("SCAN_RT");
        stepTimer = now; meshStep = 8;
    }
    // Phase 5: Final Math Time
    else if (meshStep == 8 && elapsed > 3500) { 
        float d12 = getAvg(meshRssi[0][1], meshRssi[1][0]);
        float d13 = getAvg(meshRssi[0][2], meshRssi[2][0]);
        float d23 = getAvg(meshRssi[1][2], meshRssi[2][1]);
        
        float r1 = meshRssi[0][3]; float r2 = meshRssi[1][3]; float r3 = meshRssi[2][3];
        
        SerialBT.printf("MESH,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f\n", d12, d13, d23, r1, r2, r3);
        isCalibrating = false; 
    }
    
    if (now - calibStartTime > 25000) {
       SerialBT.println("MESH_FAIL");
       isCalibrating = false;
    }
  }


  if (SerialBT.available()) {
    String btData = SerialBT.readStringUntil('\n'); btData.trim(); 
    
    if (btData == "CALIBRATE") {
      isCalibrating = true;
      meshStep = 1;
      stepTimer = millis();
      calibStartTime = millis();
      
      for(int i=0; i<3; i++) {
        for(int j=0; j<4; j++) meshRssi[i][j] = -99.0;
      }
    }
    
    else if (btData == "RESET") {
      for(int i = 0; i < NUM_SLAVES; i++) { nodes[i].totalPacketsLost = 0; nodes[i].lastPacketId = 0; nodes[i].jitter = 0; }
      display.clearDisplay(); display.setCursor(0, 20); display.println("STATS RESET!"); display.display();
      delay(500);
    }
    
    else if (btData.startsWith("WIFI:")) {
      btData.remove(0, 5); int comma = btData.indexOf(','); 
      if (comma > 0) {
        String nSSID = btData.substring(0, comma); String nPass = btData.substring(comma + 1);
        
        preferences.begin("wifi_creds", false);
        preferences.putString("ssid", nSSID); preferences.putString("pass", nPass);
        preferences.end();

        strcpy(configMsg.msgType, "WIFI_UPDT");
        strcpy(configMsg.ssid, nSSID.c_str()); strcpy(configMsg.password, nPass.c_str());

        display.clearDisplay(); display.setCursor(0, 20); display.println("UPDATING FLEET..."); display.display();
        WiFi.disconnect(); delay(100);

        for (int ch = 1; ch <= 11; ch++) {
          esp_wifi_set_promiscuous(true); esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE); esp_wifi_set_promiscuous(false);
          for(int s = 0; s < NUM_SLAVES; s++) {
            esp_now_del_peer(slaveMACs[s]);
            esp_now_peer_info_t p = {}; memcpy(p.peer_addr, slaveMACs[s], 6); p.channel = ch; esp_now_add_peer(&p);
          }
          for (int i = 0; i < 20; i++) {
            for(int s = 0; s < NUM_SLAVES; s++) { esp_now_send(slaveMACs[s], (uint8_t *) &configMsg, sizeof(configMsg)); delay(5); }
            delay(10);
          }
        }
        display.clearDisplay(); display.setCursor(0, 20); display.println("REBOOTING..."); display.display();
        delay(1000); ESP.restart(); 
      }
    }
  }

  bool anyNodeConnected = false;
  for(int i = 0; i < NUM_SLAVES; i++) {
    if (now - nodes[i].lastRecvTime <= TIMEOUT_MS && nodes[i].lastRecvTime != 0) anyNodeConnected = true;
  }

  if (!anyNodeConnected && hasWiFiCreds) {
    static unsigned long lastHop = 0;
    if (now - lastHop > 200) { 
      currentChannel++; if (currentChannel > 13) currentChannel = 1;
      esp_wifi_set_promiscuous(true); esp_wifi_set_channel(currentChannel, WIFI_SECOND_CHAN_NONE); esp_wifi_set_promiscuous(false);
      lastHop = now;
    }
  }

  if (hasWiFiCreds) { 
    static unsigned long lastDraw = 0;
    if (now - lastDraw > 500) { drawMainScreen(); lastDraw = now; }
  } else {
    drawScanningScreen(); 
  }
  delay(10);
}